package com.example.goalmaker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class WikidataSearchProvider implements RoutedSearchProvider {
    private static final Set<String> ISO_LANGUAGES = Set.of(Locale.getISOLanguages());

    private final String endpoint;
    private final WebHttpClient http;
    private final ObjectMapper mapper;
    private final int maxAttempts;
    private final long retryDelayMillis;
    private final int maxResponseBytes;

    WikidataSearchProvider(String endpoint, WebHttpClient http, ObjectMapper mapper,
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
        return "wikidata";
    }

    @Override
    public List<SearchResult> search(SearchRequest request) throws Exception {
        String language = language(request.language());
        String url = endpoint + (endpoint.contains("?") ? "&" : "?")
                + "action=wbsearchentities&format=json"
                + "&limit=" + request.maxResults()
                + "&language=" + encode(language)
                + "&uselang=" + encode(language)
                + "&search=" + encode(request.query());
        WebHttpClient.Response response = http.get(URI.create(url), "application/json",
                Duration.ofSeconds(20), maxAttempts, retryDelayMillis, maxResponseBytes);
        requireSuccess(response);

        JsonNode root = mapper.readTree(response.body());
        List<SearchResult> results = new ArrayList<>();
        for (JsonNode item : root.path("search")) {
            String id = item.path("id").asText("").trim();
            if (id.isBlank()) continue;
            String label = item.path("label").asText("").trim();
            String description = item.path("description").asText("").trim();
            results.add(new SearchResult(
                    description.isBlank() ? label : label + " - " + description,
                    "https://www.wikidata.org/wiki/" + encode(id),
                    description,
                    "",
                    name(),
                    "wikidata"));
            if (results.size() >= request.maxResults()) break;
        }
        return List.copyOf(results);
    }

    private static String language(String value) {
        if (value == null || value.isBlank() || "auto".equalsIgnoreCase(value)) return "en";
        String[] parts = value.toLowerCase(Locale.ROOT).split("[-_]");
        for (String part : parts) if (ISO_LANGUAGES.contains(part)) return part;
        return "en";
    }

    private static void requireSuccess(WebHttpClient.Response response) {
        if (response.status() / 100 != 2) {
            throw new IllegalStateException("HTTP " + response.status() + " from Wikidata");
        }
        if (response.truncated()) throw new IllegalStateException("Wikidata response exceeded size limit");
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
