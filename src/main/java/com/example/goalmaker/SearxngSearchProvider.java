package com.example.goalmaker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

final class SearxngSearchProvider implements SearchProvider {
    private final String endpoint;
    private final WebHttpClient http;
    private final ObjectMapper mapper;
    private final int maxAttempts;
    private final long retryDelayMillis;
    private final int maxResponseBytes;

    SearxngSearchProvider(String endpoint, WebHttpClient http, ObjectMapper mapper,
                         int maxAttempts, long retryDelayMillis, int maxResponseBytes) {
        this.endpoint = endpoint;
        this.http = http;
        this.mapper = mapper;
        this.maxAttempts = maxAttempts;
        this.retryDelayMillis = retryDelayMillis;
        this.maxResponseBytes = maxResponseBytes;
    }

    @Override
    public String name() {
        return "searxng";
    }

    @Override
    public List<SearchResult> search(SearchRequest request) throws Exception {
        StringBuilder url = new StringBuilder(endpoint)
                .append(endpoint.contains("?") ? "&" : "?")
                .append("q=").append(encode(request.query()))
                .append("&format=json")
                .append("&pageno=").append(request.page())
                .append("&safesearch=").append(request.safeSearch());
        if (!"auto".equalsIgnoreCase(request.language())) {
            url.append("&language=").append(encode(request.language()));
        }
        if (!request.timeRange().isBlank()) {
            url.append("&time_range=").append(encode(request.timeRange()));
        }
        if (!request.categories().isEmpty()) {
            url.append("&categories=").append(encode(String.join(",", request.categories())));
        }

        WebHttpClient.Response response = http.get(URI.create(url.toString()), "application/json",
                Duration.ofSeconds(20), maxAttempts, retryDelayMillis, maxResponseBytes);
        if (response.status() / 100 != 2) {
            throw new IllegalStateException("HTTP " + response.status() + " from SearXNG");
        }
        if (response.truncated()) throw new IllegalStateException("SearXNG response exceeded size limit");
        JsonNode root = mapper.readTree(response.body());
        List<SearchResult> results = new ArrayList<>();
        for (JsonNode result : root.path("results")) {
            String target = result.path("url").asText("");
            if (target.isBlank()) continue;
            String engine = result.path("engine").asText("");
            if (engine.isBlank() && result.path("engines").isArray()
                    && !result.path("engines").isEmpty()) {
                engine = result.path("engines").path(0).asText("");
            }
            results.add(new SearchResult(
                    result.path("title").asText("").trim(),
                    target,
                    result.path("content").asText("").trim(),
                    result.path("publishedDate").asText("").trim(),
                    name(),
                    engine));
            if (results.size() >= Math.min(100, request.maxResults() * 3)) break;
        }
        return List.copyOf(results);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
