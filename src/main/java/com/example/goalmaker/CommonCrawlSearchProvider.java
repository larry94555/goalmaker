package com.example.goalmaker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class CommonCrawlSearchProvider implements RoutedSearchProvider {
    private static final Pattern TARGET = Pattern.compile(
            "(?i)(https?://[^\\s<>\"']+|(?:[a-z0-9-]+\\.)+[a-z]{2,}(?:/[^\\s<>\"']*)?)");
    private static final DateTimeFormatter CAPTURE_DATE = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final String collectionsEndpoint;
    private final WebHttpClient http;
    private final ObjectMapper mapper;
    private final int maxAttempts;
    private final long retryDelayMillis;
    private final int maxResponseBytes;
    private volatile IndexEndpoint currentIndex;

    CommonCrawlSearchProvider(String collectionsEndpoint, WebHttpClient http, ObjectMapper mapper,
                              int maxAttempts, long retryDelayMillis, int maxResponseBytes) {
        this.collectionsEndpoint = collectionsEndpoint;
        this.http = http;
        this.mapper = mapper;
        this.maxAttempts = maxAttempts;
        this.retryDelayMillis = retryDelayMillis;
        this.maxResponseBytes = maxResponseBytes;
    }

    @Override
    public SearchIntent intent() {
        return SearchIntent.ARCHIVAL;
    }

    @Override
    public String name() {
        return "commoncrawl";
    }

    @Override
    public List<SearchResult> search(SearchRequest request) throws Exception {
        String target = target(request.query());
        if (target.isBlank()) return List.of();
        IndexEndpoint index = latestIndex();
        String url = index.url() + (index.url().contains("?") ? "&" : "?")
                + "url=" + encode(target)
                + "&output=json&filter=status%3A200&filter=mime%3Atext%2Fhtml&collapse=digest";
        WebHttpClient.Response response = http.get(URI.create(url), "application/x-ndjson",
                Duration.ofSeconds(30), maxAttempts, retryDelayMillis, maxResponseBytes);
        requireSuccess(response, "Common Crawl index");

        List<Capture> captures = new ArrayList<>();
        for (String line : response.body().split("\\R")) {
            if (line.isBlank()) continue;
            JsonNode item = mapper.readTree(line);
            String capturedUrl = item.path("url").asText("").trim();
            if (capturedUrl.isBlank()) continue;
            captures.add(new Capture(capturedUrl,
                    item.path("timestamp").asText("").trim(),
                    item.path("mime").asText("").trim(),
                    item.path("digest").asText("").trim()));
        }
        captures.sort(Comparator.comparing(Capture::timestamp).reversed());
        Map<String, Capture> unique = new LinkedHashMap<>();
        for (Capture capture : captures) unique.putIfAbsent(capture.url(), capture);

        List<SearchResult> results = new ArrayList<>();
        for (Capture capture : unique.values()) {
            URI captured = URI.create(capture.url());
            String title = "Common Crawl capture: " + captured.getHost()
                    + (captured.getPath() == null ? "" : captured.getPath());
            results.add(new SearchResult(
                    title,
                    capture.url(),
                    "Archived in " + index.id() + " at " + normalizeDate(capture.timestamp())
                            + (capture.mime().isBlank() ? "" : " as " + capture.mime()),
                    normalizeDate(capture.timestamp()),
                    name(),
                    index.id()));
            if (results.size() >= request.maxResults()) break;
        }
        return List.copyOf(results);
    }

    private synchronized IndexEndpoint latestIndex() throws Exception {
        IndexEndpoint cached = currentIndex;
        if (cached != null && cached.expiresAt().isAfter(Instant.now())) return cached;
        WebHttpClient.Response response = http.get(URI.create(collectionsEndpoint), "application/json",
                Duration.ofSeconds(20), maxAttempts, retryDelayMillis, maxResponseBytes);
        requireSuccess(response, "Common Crawl collection list");
        JsonNode collections = mapper.readTree(response.body());
        if (!collections.isArray() || collections.isEmpty()) {
            throw new IllegalStateException("Common Crawl returned no indexes");
        }
        JsonNode latest = collections.path(0);
        String url = latest.path("cdx-api").asText("").trim();
        String id = latest.path("id").asText("").trim();
        if (url.isBlank()) throw new IllegalStateException("Common Crawl latest index has no API URL");
        currentIndex = new IndexEndpoint(id.isBlank() ? "commoncrawl-latest" : id,
                url, Instant.now().plus(Duration.ofHours(24)));
        return currentIndex;
    }

    private static String target(String query) {
        Matcher matcher = TARGET.matcher(query);
        if (!matcher.find()) return "";
        String value = matcher.group(1).replaceFirst("[.,;:!?)\\]}]+$", "");
        if (!value.toLowerCase(Locale.ROOT).startsWith("http://")
                && !value.toLowerCase(Locale.ROOT).startsWith("https://")) {
            value = value.contains("/") ? value : value + "/*";
        }
        return value;
    }

    private static String normalizeDate(String value) {
        try {
            return LocalDateTime.parse(value, CAPTURE_DATE).toInstant(ZoneOffset.UTC).toString();
        } catch (Exception ignored) {
            return value == null ? "" : value.trim();
        }
    }

    private static void requireSuccess(WebHttpClient.Response response, String provider) {
        if (response.status() / 100 != 2) {
            throw new IllegalStateException("HTTP " + response.status() + " from " + provider);
        }
        if (response.truncated()) throw new IllegalStateException(provider + " response exceeded size limit");
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private record IndexEndpoint(String id, String url, Instant expiresAt) {}

    private record Capture(String url, String timestamp, String mime, String digest) {}
}
