package com.example.goalmaker;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class WebSearchToolProvider {
    @Value("${web.search.searxng-url:http://127.0.0.1:8888/search}")
    private String searxngUrl = "http://127.0.0.1:8888/search";
    @Value("${web.search.duckduckgo-url:${web.search.endpoint:https://html.duckduckgo.com/html/}}")
    private String duckDuckGoUrl = "https://html.duckduckgo.com/html/";
    @Value("${web.search.max-attempts:2}") private int maxAttempts = 2;
    @Value("${web.search.retry-delay-millis:250}") private long retryDelayMillis = 250;
    @Value("${web.search.max-response-bytes:1048576}") private int maxResponseBytes = 1_048_576;
    @Value("${web.search.cache-ttl-seconds:300}") private long cacheTtlSeconds = 300;
    @Value("${web.search.cache-max-entries:100}") private int cacheMaxEntries = 100;

    private final ObjectMapper mapper;
    private final WebHttpClient http;
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    public WebSearchToolProvider() {
        this(new ObjectMapper());
    }

    @Autowired
    public WebSearchToolProvider(ObjectMapper mapper) {
        this(mapper, HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(10))
                .build());
    }

    WebSearchToolProvider(ObjectMapper mapper, HttpClient client) {
        this.mapper = mapper;
        this.http = new WebHttpClient(client);
    }

    public List<ToolDefinition> tools() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("query", stringProperty("Search query."));
        properties.put("max_results", integerProperty("Maximum number of results (1-20).", 1, 20));
        properties.put("language", stringProperty("Language or region code, or auto."));
        properties.put("time_range", Map.of("type", "string",
                "description", "Optional recency filter.", "enum", List.of("day", "month", "year")));
        properties.put("page", integerProperty("Search result page (1-10).", 1, 10));
        properties.put("safe_search", integerProperty("Safe search: 0 off, 1 moderate, 2 strict.", 0, 2));
        properties.put("categories", Map.of("type", "array",
                "description", "Optional SearXNG categories such as general, news, science, or it.",
                "items", Map.of("type", "string")));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("query"));
        return List.of(new ToolDefinition(
                "web_search",
                "Search the web using local SearXNG with resilient DuckDuckGo fallback. Returns structured, sourced results.",
                schema,
                "builtin:web_search",
                true,
                this::search));
    }

    private String search(Map<String, Object> arguments) throws Exception {
        SearchRequest request = SearchRequest.from(arguments);
        CacheEntry cached = cache.get(request.cacheKey());
        if (cached != null && cached.expiresAt().isAfter(Instant.now())) {
            Map<String, Object> payload = new LinkedHashMap<>(cached.payload());
            payload.put("cached", true);
            return mapper.writeValueAsString(payload);
        }

        List<SearchProvider> providers = providers();
        List<String> attempted = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        List<SearchResult> selected = List.of();
        String selectedProvider = "none";
        for (SearchProvider provider : providers) {
            attempted.add(provider.name());
            try {
                List<SearchResult> results = deduplicate(provider.search(request), request.maxResults());
                if (!results.isEmpty()) {
                    selected = results;
                    selectedProvider = provider.name();
                    break;
                }
                errors.add(provider.name() + ": no results");
            } catch (Exception error) {
                errors.add(provider.name() + ": " + usefulMessage(error));
            }
        }

        Map<String, Object> payload = payload(request, selectedProvider, attempted, errors, selected);
        if (!selected.isEmpty()) cache(request.cacheKey(), payload);
        return mapper.writeValueAsString(payload);
    }

    private List<SearchProvider> providers() {
        List<SearchProvider> providers = new ArrayList<>();
        if (searxngUrl != null && !searxngUrl.isBlank()) {
            providers.add(new SearxngSearchProvider(searxngUrl, http, mapper,
                    maxAttempts, retryDelayMillis, maxResponseBytes));
        }
        if (duckDuckGoUrl != null && !duckDuckGoUrl.isBlank()) {
            providers.add(new DuckDuckGoSearchProvider(duckDuckGoUrl, http,
                    maxAttempts, retryDelayMillis, maxResponseBytes));
        }
        return providers;
    }

    private Map<String, Object> payload(SearchRequest request, String provider, List<String> attempted,
                                        List<String> errors, List<SearchResult> results) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("query", request.query());
        payload.put("provider", provider);
        payload.put("providers_attempted", attempted);
        payload.put("cached", false);
        payload.put("retrieved_at", Instant.now().toString());
        payload.put("result_count", results.size());
        List<Map<String, Object>> serialized = new ArrayList<>();
        for (int index = 0; index < results.size(); index++) {
            SearchResult result = results.get(index);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("rank", index + 1);
            item.put("title", result.title());
            item.put("url", result.url());
            item.put("snippet", result.snippet());
            if (!result.publishedAt().isBlank()) item.put("published_at", result.publishedAt());
            item.put("provider", result.provider());
            if (!result.engine().isBlank()) item.put("engine", result.engine());
            serialized.add(item);
        }
        payload.put("results", serialized);
        if (!errors.isEmpty()) payload.put("provider_notes", errors);
        return payload;
    }

    private void cache(String key, Map<String, Object> payload) {
        if (cacheTtlSeconds <= 0 || cacheMaxEntries <= 0) return;
        Instant now = Instant.now();
        cache.entrySet().removeIf(entry -> entry.getValue().expiresAt().isBefore(now));
        if (cache.size() >= cacheMaxEntries) {
            cache.keySet().stream().findFirst().ifPresent(cache::remove);
        }
        cache.put(key, new CacheEntry(Map.copyOf(payload), now.plusSeconds(cacheTtlSeconds)));
    }

    private static List<SearchResult> deduplicate(List<SearchResult> results, int maximum) {
        List<SearchResult> unique = new ArrayList<>();
        Set<String> urls = new LinkedHashSet<>();
        for (SearchResult result : results) {
            String canonical = canonicalUrl(result.url());
            if (!canonical.isBlank() && urls.add(canonical)) unique.add(result);
            if (unique.size() >= maximum) break;
        }
        return List.copyOf(unique);
    }

    private static String canonicalUrl(String value) {
        try {
            URI uri = URI.create(value).normalize();
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            if (scheme.isBlank() || host.isBlank()) return value;
            int port = uri.getPort();
            if (("http".equals(scheme) && port == 80) || ("https".equals(scheme) && port == 443)) port = -1;
            return new URI(scheme, null, host, port, uri.getPath(), uri.getQuery(), null).toString();
        } catch (Exception error) {
            return value;
        }
    }

    private static String usefulMessage(Exception error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }

    private static Map<String, Object> stringProperty(String description) {
        return Map.of("type", "string", "description", description);
    }

    private static Map<String, Object> integerProperty(String description, int minimum, int maximum) {
        return Map.of("type", "integer", "description", description,
                "minimum", minimum, "maximum", maximum);
    }

    private record CacheEntry(Map<String, Object> payload, Instant expiresAt) {}
}
