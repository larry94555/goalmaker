package com.example.goalmaker;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
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

@Component
public class WebFetchToolProvider {
    @Value("${web.fetch.max-attempts:2}") private int maxAttempts = 2;
    @Value("${web.fetch.retry-delay-millis:250}") private long retryDelayMillis = 250;
    @Value("${web.fetch.max-response-bytes:8388608}") private int maxResponseBytes = 8_388_608;
    @Value("${web.fetch.max-redirects:5}") private int maxRedirects = 5;
    @Value("${web.fetch.default-max-chars:12000}") private int defaultMaxChars = 12_000;
    @Value("${web.fetch.allow-private-addresses:false}") private boolean allowPrivateAddresses;
    @Value("${web.fetch.robots.enabled:true}") private boolean robotsEnabled = true;
    @Value("${web.fetch.robots.timeout-seconds:5}") private int robotsTimeoutSeconds = 5;
    @Value("${web.fetch.robots.max-attempts:1}") private int robotsMaxAttempts = 1;
    @Value("${web.fetch.robots.retry-delay-millis:250}") private long robotsRetryDelayMillis = 250;
    @Value("${web.fetch.robots.max-response-bytes:524288}") private int robotsMaxResponseBytes = 524_288;
    @Value("${web.fetch.robots.max-redirects:5}") private int robotsMaxRedirects = 5;
    @Value("${web.fetch.robots.cache-ttl-seconds:3600}") private int robotsCacheTtlSeconds = 3_600;
    @Value("${web.fetch.robots.cache-max-entries:200}") private int robotsCacheMaxEntries = 200;
    @Value("${web.fetch.pdf.max-pages:40}") private int pdfMaxPages = 40;
    @Value("${web.fetch.pdf.timeout-seconds:20}") private int pdfTimeoutSeconds = 20;

    private final ObjectMapper mapper;
    private final WebHttpClient http;
    private final RobotsPolicy robots;
    private final HtmlDocumentExtractor htmlExtractor;
    private final PdfDocumentExtractor pdfExtractor;

    public WebFetchToolProvider() {
        this(new ObjectMapper());
    }

    @Autowired
    public WebFetchToolProvider(ObjectMapper mapper) {
        this(mapper, HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(10))
                .build());
    }

    WebFetchToolProvider(ObjectMapper mapper, HttpClient client) {
        this.mapper = mapper;
        this.http = new WebHttpClient(client);
        this.robots = new RobotsPolicy(http);
        this.htmlExtractor = new HtmlDocumentExtractor(mapper);
        this.pdfExtractor = new PdfDocumentExtractor();
    }

    public List<ToolDefinition> tools() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("url", Map.of("type", "string",
                "description", "Absolute public http(s) URL returned by web_search."));
        properties.put("max_chars", Map.of("type", "integer",
                "description", "Maximum readable characters to return (1000-20000).",
                "minimum", 1_000, "maximum", 20_000));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("url"));
        return List.of(new ToolDefinition(
                "web_fetch",
                "Fetch a robots-permitted public HTML, text, or PDF document and return readable text with source provenance.",
                schema,
                "builtin:web_fetch",
                true,
                this::fetch));
    }

    private String fetch(Map<String, Object> arguments) throws Exception {
        return mapper.writeValueAsString(fetchPayload(arguments));
    }

    Map<String, Object> fetchPayload(Map<String, Object> arguments) throws Exception {
        String requested = arguments.get("url") == null ? "" : String.valueOf(arguments.get("url")).trim();
        if (requested.isBlank()) throw new IllegalArgumentException("url is required");
        int maxChars = integer(arguments.get("max_chars"), defaultMaxChars, 1_000, 20_000);
        URI current = URI.create(requested);
        WebHttpClient.BinaryResponse response = null;
        RobotsPolicy.Decision robotsDecision = null;
        for (int redirect = 0; redirect <= Math.max(0, maxRedirects); redirect++) {
            validatePublicHttpUrl(current);
            robotsDecision = robots.check(current, robotsSettings(), this::validatePublicHttpUrl);
            if (!robotsDecision.allowed()) {
                throw new IllegalStateException("robots.txt disallows fetching " + current
                        + (robotsDecision.reason().isBlank() ? "" : ": " + robotsDecision.reason()));
            }
            response = http.getBytes(current,
                    "text/html, application/xhtml+xml, application/pdf, text/plain;q=0.9, */*;q=0.1",
                    Duration.ofSeconds(25), maxAttempts, retryDelayMillis, maxResponseBytes);
            if (!redirect(response.status())) break;
            String location = response.headers().firstValue("Location")
                    .orElseThrow(() -> new IllegalStateException("redirect response had no Location header"));
            current = current.resolve(location);
            if (redirect == maxRedirects) throw new IllegalStateException("too many redirects");
        }
        if (response == null || response.status() / 100 != 2) {
            throw new IllegalStateException("HTTP " + (response == null ? "unknown" : response.status())
                    + " when fetching " + current);
        }

        String declaredType = response.headers().firstValue("Content-Type").orElse("")
                .split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        String contentType = detectedContentType(current, declaredType, response.body());
        List<String> truncationReasons = new ArrayList<>();
        if (response.truncated()) truncationReasons.add("download-byte-limit");

        ExtractedDocument extracted;
        if (contentType.equals("application/pdf")) {
            if (response.truncated()) {
                throw new IllegalStateException("PDF exceeded the configured download byte limit");
            }
            PdfDocumentExtractor.Extraction pdf = extractPdf(response.body());
            if (pdf.pagesExtracted() < pdf.pageCount()) truncationReasons.add("page-limit");
            extracted = new ExtractedDocument(pdf.text(), pdf.title(), "", pdf.author(),
                    pdf.publishedAt(), pdf.modifiedAt(), "", pdf.method(), List.of(),
                    pdf.pageCount(), pdf.pagesExtracted());
        } else if (contentType.equals("text/html") || contentType.equals("application/xhtml+xml")) {
            HtmlDocumentExtractor.Extraction html = htmlExtractor.extract(WebHttpClient.decode(response), current);
            extracted = new ExtractedDocument(html.text(), html.title(), html.canonicalUrl(), html.author(),
                    html.publishedAt(), html.modifiedAt(), html.language(), html.method(),
                    html.metadataConflicts(), null, null);
        } else if (contentType.startsWith("text/")) {
            extracted = new ExtractedDocument(normalize(WebHttpClient.decode(response)), "", "", "", "",
                    "", "", "plain-text", List.of(), null, null);
        } else {
            throw new IllegalStateException("unsupported content type "
                    + (contentType.isBlank() ? "unknown" : contentType));
        }
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
        payload.put("retrieved_at", Instant.now().toString());
        payload.put("truncated", !truncationReasons.isEmpty());
        if (!truncationReasons.isEmpty()) payload.put("truncation_reasons", List.copyOf(truncationReasons));
        payload.put("content", text);
        return payload;
    }

    private PdfDocumentExtractor.Extraction extractPdf(byte[] bytes) throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "web-fetch-pdf");
            thread.setDaemon(true);
            return thread;
        });
        Future<PdfDocumentExtractor.Extraction> future = executor.submit(
                () -> pdfExtractor.extract(bytes, pdfMaxPages));
        try {
            return future.get(Math.max(1, pdfTimeoutSeconds), TimeUnit.SECONDS);
        } catch (TimeoutException error) {
            future.cancel(true);
            throw new IllegalStateException("PDF extraction exceeded the configured time limit", error);
        } catch (ExecutionException error) {
            Throwable cause = error.getCause();
            if (cause instanceof Exception exception) throw exception;
            throw new IllegalStateException("PDF extraction failed", cause);
        } finally {
            executor.shutdownNow();
        }
    }

    private RobotsPolicy.Settings robotsSettings() {
        return new RobotsPolicy.Settings(robotsEnabled, Duration.ofSeconds(Math.max(1, robotsTimeoutSeconds)),
                Math.max(1, robotsMaxAttempts), Math.max(0, robotsRetryDelayMillis),
                Math.max(1, robotsMaxResponseBytes), Math.max(0, robotsMaxRedirects),
                Duration.ofSeconds(Math.max(0, robotsCacheTtlSeconds)), Math.max(0, robotsCacheMaxEntries));
    }

    private void validatePublicHttpUrl(URI uri) throws Exception {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!("http".equals(scheme) || "https".equals(scheme)) || uri.getHost() == null) {
            throw new IllegalArgumentException("url must be an absolute http(s) URL");
        }
        if (uri.getUserInfo() != null) throw new IllegalArgumentException("url must not contain credentials");
        if (allowPrivateAddresses) return;
        for (InetAddress address : InetAddress.getAllByName(uri.getHost())) {
            if (privateAddress(address)) {
                throw new IllegalArgumentException("url resolves to a private or local network address");
            }
        }
    }

    private static boolean privateAddress(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) return true;
        if (address instanceof Inet6Address) {
            byte[] bytes = address.getAddress();
            return bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc;
        }
        byte[] bytes = address.getAddress();
        int first = Byte.toUnsignedInt(bytes[0]);
        int second = bytes.length > 1 ? Byte.toUnsignedInt(bytes[1]) : 0;
        return first == 0 || first == 10 || first == 127 || first >= 224
                || (first == 100 && second >= 64 && second <= 127)
                || (first == 169 && second == 254)
                || (first == 172 && second >= 16 && second <= 31)
                || (first == 192 && second == 168);
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
