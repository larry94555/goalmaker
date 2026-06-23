package com.example.goalmaker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jsoup.Jsoup;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

final class MediaWikiSearchProvider implements RoutedSearchProvider {
    private final String endpoint;
    private final WebHttpClient http;
    private final ObjectMapper mapper;
    private final int maxAttempts;
    private final long retryDelayMillis;
    private final int maxResponseBytes;

    MediaWikiSearchProvider(String endpoint, WebHttpClient http, ObjectMapper mapper,
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
        return SearchIntent.FACTUAL_ENTITY;
    }

    @Override
    public String name() {
        return "mediawiki";
    }

    @Override
    public List<SearchResult> search(SearchRequest request) throws Exception {
        String url = endpoint + (endpoint.contains("?") ? "&" : "?")
                + "action=query&list=search&format=json&utf8=1"
                + "&srprop=snippet%7Ctimestamp"
                + "&srlimit=" + request.maxResults()
                + "&srsearch=" + encode(request.query());
        WebHttpClient.Response response = http.get(URI.create(url), "application/json",
                Duration.ofSeconds(20), maxAttempts, retryDelayMillis, maxResponseBytes);
        requireSuccess(response, "MediaWiki");

        URI api = URI.create(endpoint);
        String root = api.getScheme() + "://" + api.getAuthority();
        JsonNode rootNode = mapper.readTree(response.body());
        List<SearchResult> results = new ArrayList<>();
        for (JsonNode item : rootNode.path("query").path("search")) {
            String pageId = item.path("pageid").asText("");
            if (pageId.isBlank()) continue;
            results.add(new SearchResult(
                    item.path("title").asText("").trim(),
                    root + "/?curid=" + encode(pageId),
                    Jsoup.parse(item.path("snippet").asText("")).text().trim(),
                    item.path("timestamp").asText("").trim(),
                    name(),
                    api.getHost()));
            if (results.size() >= request.maxResults()) break;
        }
        return List.copyOf(results);
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
}
