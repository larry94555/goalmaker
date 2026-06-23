package com.example.goalmaker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

final class GdeltSearchProvider implements RoutedSearchProvider {
    private static final DateTimeFormatter GDELT_DATE = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'");

    private final String endpoint;
    private final WebHttpClient http;
    private final ObjectMapper mapper;
    private final int maxAttempts;
    private final long retryDelayMillis;
    private final int maxResponseBytes;

    GdeltSearchProvider(String endpoint, WebHttpClient http, ObjectMapper mapper,
                        int maxAttempts, long retryDelayMillis, int maxResponseBytes) {
        this.endpoint = endpoint;
        this.http = http;
        this.mapper = mapper;
        this.maxAttempts = maxAttempts;
        this.retryDelayMillis = retryDelayMillis;
        this.maxResponseBytes = maxResponseBytes;
    }

    @Override
    public SearchIntent intent() {
        return SearchIntent.CURRENT_NEWS;
    }

    @Override
    public String name() {
        return "gdelt";
    }

    @Override
    public List<SearchResult> search(SearchRequest request) throws Exception {
        String url = endpoint + (endpoint.contains("?") ? "&" : "?")
                + "query=" + encode(request.query())
                + "&mode=ArtList&format=json&sort=HybridRel"
                + "&maxrecords=" + Math.max(10, request.maxResults())
                + "&timespan=" + timespan(request.timeRange());
        WebHttpClient.Response response = http.get(URI.create(url), "application/json",
                Duration.ofSeconds(30), maxAttempts, retryDelayMillis, maxResponseBytes);
        requireSuccess(response);

        JsonNode root = mapper.readTree(response.body());
        List<SearchResult> results = new ArrayList<>();
        for (JsonNode article : root.path("articles")) {
            String target = article.path("url").asText("").trim();
            if (target.isBlank()) continue;
            String domain = article.path("domain").asText("").trim();
            String sourceCountry = article.path("sourcecountry").asText("").trim();
            String language = article.path("language").asText("").trim();
            List<String> details = new ArrayList<>();
            if (!domain.isBlank()) details.add(domain);
            if (!sourceCountry.isBlank()) details.add(sourceCountry);
            if (!language.isBlank()) details.add(language);
            results.add(new SearchResult(
                    article.path("title").asText("").trim(),
                    target,
                    details.isEmpty() ? "GDELT news result" : "GDELT news result: " + String.join(", ", details),
                    normalizeDate(article.path("seendate").asText("")),
                    name(),
                    domain));
            if (results.size() >= request.maxResults()) break;
        }
        return List.copyOf(results);
    }

    private static String timespan(String timeRange) {
        return switch (timeRange) {
            case "day" -> "24h";
            case "year" -> "1year";
            case "month" -> "1month";
            default -> "1month";
        };
    }

    private static String normalizeDate(String value) {
        try {
            return LocalDateTime.parse(value, GDELT_DATE).toInstant(ZoneOffset.UTC).toString();
        } catch (Exception ignored) {
            return value == null ? "" : value.trim();
        }
    }

    private static void requireSuccess(WebHttpClient.Response response) {
        if (response.status() / 100 != 2) {
            throw new IllegalStateException("HTTP " + response.status() + " from GDELT");
        }
        if (response.truncated()) throw new IllegalStateException("GDELT response exceeded size limit");
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
