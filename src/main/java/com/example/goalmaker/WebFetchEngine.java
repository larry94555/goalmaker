package com.example.goalmaker;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

final class WebFetchEngine {
    private final RobotsPolicy robots;
    private final HtmlDocumentExtractor htmlExtractor;
    private final PdfDocumentExtractor pdfExtractor;

    WebFetchEngine(ObjectMapper mapper, RobotsPolicy robots) {
        this.robots = robots;
        this.htmlExtractor = new HtmlDocumentExtractor(mapper);
        this.pdfExtractor = new PdfDocumentExtractor();
    }

    Map<String, Object> fetchPayload(Map<String, Object> arguments, FetchSettings settings) throws Exception {
        String requested = arguments.get("url") == null ? "" : String.valueOf(arguments.get("url")).trim();
        if (requested.isBlank()) throw new IllegalArgumentException("url is required");
        int maxChars = integer(arguments.get("max_chars"), settings.defaultMaxChars(), 1_000, 20_000);
        FetchBudget budget = new FetchBudget(Duration.ofSeconds(Math.max(1, settings.totalBudgetSeconds())),
                settings.maxHttpRequests());
        PinnedDns dns = new PinnedDns(settings.allowPrivateAddresses());
        FetchUrlPolicy urlPolicy = new FetchUrlPolicy(dns, settings.allowedPorts());
        FetchHttpClient http = new PinnedHttpClient(dns);

        URI current = URI.create(requested);
        FetchHttpClient.Response response = null;
        RobotsPolicy.Decision robotsDecision = null;
        for (int redirect = 0; redirect <= Math.max(0, settings.maxRedirects()); redirect++) {
            budget.check("web fetch total time budget exceeded");
            urlPolicy.validate(current);
            robotsDecision = robots.check(current, robotsSettings(settings), urlPolicy::validate, http, budget);
            if (!robotsDecision.allowed()) {
                throw new IllegalStateException("robots.txt disallows fetching " + current
                        + (robotsDecision.reason().isBlank() ? "" : ": " + robotsDecision.reason()));
            }
            response = http.getBytes(current,
                    "text/html, application/xhtml+xml, application/pdf, text/plain;q=0.9, */*;q=0.1",
                    Duration.ofSeconds(25), settings.maxAttempts(), settings.retryDelayMillis(),
                    settings.maxResponseBytes(), budget);
            if (!redirect(response.status())) break;
            String location = response.firstHeader("Location")
                    .orElseThrow(() -> new IllegalStateException("redirect response had no Location header"));
            current = current.resolve(location);
            if (redirect == settings.maxRedirects()) throw new IllegalStateException("too many redirects");
        }
        if (response == null || response.status() / 100 != 2) {
            throw new IllegalStateException("HTTP " + (response == null ? "unknown" : response.status())
                    + " when fetching " + current);
        }

        budget.check("web fetch total time budget exceeded");
        String declaredType = response.firstHeader("Content-Type").orElse("")
                .split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        String contentType = detectedContentType(current, declaredType, response.body());
        List<String> truncationReasons = new ArrayList<>();
        if (response.truncated()) truncationReasons.add("download-byte-limit");

        ExtractedDocument extracted;
        if (contentType.equals("application/pdf")) {
            if (response.truncated()) {
                throw new IllegalStateException("PDF exceeded the configured download byte limit");
            }
            PdfDocumentExtractor.Extraction pdf = extractPdf(response.body(), settings, budget);
            if (pdf.pagesExtracted() < pdf.pageCount()) truncationReasons.add("page-limit");
            extracted = new ExtractedDocument(pdf.text(), pdf.title(), "", pdf.author(),
                    pdf.publishedAt(), pdf.modifiedAt(), "", pdf.method(), List.of(),
                    pdf.pageCount(), pdf.pagesExtracted());
        } else if (contentType.equals("text/html") || contentType.equals("application/xhtml+xml")) {
            HtmlDocumentExtractor.Extraction html = htmlExtractor.extract(response.decode(), current);
            extracted = new ExtractedDocument(html.text(), html.title(), html.canonicalUrl(), html.author(),
                    html.publishedAt(), html.modifiedAt(), html.language(), html.method(),
                    html.metadataConflicts(), null, null);
        } else if (contentType.startsWith("text/")) {
            extracted = new ExtractedDocument(normalize(response.decode()), "", "", "", "",
                    "", "", "plain-text", List.of(), null, null);
        } else {
            throw new IllegalStateException("unsupported content type "
                    + (contentType.isBlank() ? "unknown" : contentType));
        }
        budget.check("web fetch total time budget exceeded");
        if (extracted.text().isBlank()) throw new IllegalStateException("document contained no readable text");

        String text = extracted.text();
        if (text.length() > maxChars) {
            text = text.substring(0, maxChars);
            truncationReasons.add("character-limit");
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("requested_url", requested);
        payload.put("url", current.toString());
        put(payload, "canonical_url", extracted.canonicalUrl());
        payload.put("title", extracted.title());
        put(payload, "author", extracted.author());
        put(payload, "published_at", extracted.publishedAt());
        put(payload, "modified_at", extracted.modifiedAt());
        put(payload, "language", extracted.language());
        payload.put("content_type", contentType);
        payload.put("extraction_method", extracted.method());
        payload.put("download_bytes", response.body().length);
        if (extracted.pageCount() != null) payload.put("page_count", extracted.pageCount());
        if (extracted.pagesExtracted() != null) payload.put("pages_extracted", extracted.pagesExtracted());
        if (!extracted.metadataConflicts().isEmpty()) {
            payload.put("metadata_conflicts", extracted.metadataConflicts());
        }
        if (robotsDecision != null) {
            Map<String, Object> robotsPayload = new LinkedHashMap<>();
            robotsPayload.put("allowed", robotsDecision.allowed());
            robotsPayload.put("status", robotsDecision.status());
            put(robotsPayload, "url", robotsDecision.robotsUrl());
            robotsPayload.put("cached", robotsDecision.cached());
            payload.put("robots", robotsPayload);
        }
        Map<String, Object> policy = new LinkedHashMap<>();
        policy.put("dns_pinned", true);
        policy.put("allowed_ports", settings.allowedPorts().stream().sorted().toList());
        policy.put("total_budget_seconds", settings.totalBudgetSeconds());
        policy.put("max_http_requests", settings.maxHttpRequests());
        payload.put("fetch_policy", policy);
        payload.put("retrieved_at", Instant.now().toString());
        payload.put("truncated", !truncationReasons.isEmpty());
        if (!truncationReasons.isEmpty()) payload.put("truncation_reasons", List.copyOf(truncationReasons));
        payload.put("content", text);
        return payload;
    }

    private PdfDocumentExtractor.Extraction extractPdf(byte[] bytes, FetchSettings settings,
                                                       FetchBudget budget) throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "web-fetch-pdf");
            thread.setDaemon(true);
            return thread;
        });
        Future<PdfDocumentExtractor.Extraction> future = executor.submit(
                () -> pdfExtractor.extract(bytes, settings.pdfMaxPages()));
        try {
            long timeout = Math.min(TimeUnit.SECONDS.toMillis(Math.max(1, settings.pdfTimeoutSeconds())),
                    budget.remainingMillis());
            if (timeout <= 0) throw new IllegalStateException("web fetch total time budget exceeded");
            return future.get(timeout, TimeUnit.MILLISECONDS);
        } catch (TimeoutException error) {
            future.cancel(true);
            if (budget.remainingMillis() <= 0) {
                throw new IllegalStateException("web fetch total time budget exceeded", error);
            }
            throw new IllegalStateException("PDF extraction exceeded the configured time limit", error);
        } catch (ExecutionException error) {
            Throwable cause = error.getCause();
            if (cause instanceof Exception exception) throw exception;
            throw new IllegalStateException("PDF extraction failed", cause);
        } finally {
            executor.shutdownNow();
        }
    }

    private static RobotsPolicy.Settings robotsSettings(FetchSettings settings) {
        return new RobotsPolicy.Settings(settings.robotsEnabled(),
                Duration.ofSeconds(Math.max(1, settings.robotsTimeoutSeconds())),
                Math.max(1, settings.robotsMaxAttempts()), Math.max(0, settings.robotsRetryDelayMillis()),
                Math.max(1, settings.robotsMaxResponseBytes()), Math.max(0, settings.robotsMaxRedirects()),
                Duration.ofSeconds(Math.max(0, settings.robotsCacheTtlSeconds())),
                Math.max(0, settings.robotsCacheMaxEntries()));
    }

    private static String detectedContentType(URI uri, String declared, byte[] bytes) {
        if (pdf(bytes) || declared.equals("application/pdf")
                || uri.getPath() != null && uri.getPath().toLowerCase(Locale.ROOT).endsWith(".pdf")) {
            return "application/pdf";
        }
        if (!declared.isBlank()) return declared;
        String prefix = new String(bytes, 0, Math.min(bytes.length, 256), java.nio.charset.StandardCharsets.US_ASCII)
                .trim().toLowerCase(Locale.ROOT);
        return prefix.startsWith("<!doctype html") || prefix.startsWith("<html")
                ? "text/html" : "application/octet-stream";
    }

    private static boolean pdf(byte[] bytes) {
        return bytes.length >= 5 && bytes[0] == '%' && bytes[1] == 'P' && bytes[2] == 'D'
                && bytes[3] == 'F' && bytes[4] == '-';
    }

    private static void put(Map<String, Object> payload, String key, String value) {
        if (value != null && !value.isBlank()) payload.put(key, value);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }

    private static boolean redirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    private static int integer(Object value, int fallback, int minimum, int maximum) {
        int parsed;
        try {
            parsed = value == null ? fallback : Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("max_chars is invalid");
        }
        if (parsed < minimum || parsed > maximum) {
            throw new IllegalArgumentException("max_chars must be between " + minimum + " and " + maximum);
        }
        return parsed;
    }

    private record ExtractedDocument(String text, String title, String canonicalUrl, String author,
                                     String publishedAt, String modifiedAt, String language, String method,
                                     List<Map<String, Object>> metadataConflicts, Integer pageCount,
                                     Integer pagesExtracted) {}
}
