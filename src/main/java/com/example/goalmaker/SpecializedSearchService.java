package com.example.goalmaker;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

@Component
public class SpecializedSearchService {
    private final List<RoutedSearchProvider> providers;
    private final Map<String, Duration> cacheTtls;
    private final Map<String, Duration> rateLimits;
    private final int cacheMaxEntries;
    private final int maxConcurrency;
    private final Duration timeout;
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();
    private final Map<String, Instant> lastCalls = new ConcurrentHashMap<>();
    private final Map<String, Object> rateLocks = new ConcurrentHashMap<>();

    @Autowired
    public SpecializedSearchService(ObjectMapper mapper, Environment environment) {
        this(buildProviders(mapper, environment),
                cacheTtls(environment), rateLimits(environment),
                integer(environment, "web.specialized.cache-max-entries", 200),
                integer(environment, "web.specialized.max-concurrency", 4),
                Duration.ofSeconds(integer(environment, "web.specialized.timeout-seconds", 40)));
    }

    SpecializedSearchService(List<RoutedSearchProvider> providers,
                             Map<String, Duration> cacheTtls,
                             Map<String, Duration> rateLimits,
                             int cacheMaxEntries,
                             int maxConcurrency,
                             Duration timeout) {
        this.providers = List.copyOf(providers);
        this.cacheTtls = Map.copyOf(cacheTtls);
        this.rateLimits = Map.copyOf(rateLimits);
        this.cacheMaxEntries = Math.max(0, cacheMaxEntries);
        this.maxConcurrency = Math.max(1, maxConcurrency);
        this.timeout = timeout.isNegative() || timeout.isZero() ? Duration.ofSeconds(1) : timeout;
    }

    SearchOutcome search(SearchRequest request) {
        List<SearchIntent> intents = QueryIntentClassifier.classify(request);
        List<RoutedSearchProvider> selected = providers.stream()
                .filter(provider -> intents.contains(provider.intent()))
                .toList();
        if (selected.isEmpty()) return new SearchOutcome(intentValues(intents), List.of(), List.of(), List.of());

        int threads = Math.min(maxConcurrency, selected.size());
        ExecutorService executor = Executors.newFixedThreadPool(threads, runnable -> {
            Thread thread = new Thread(runnable, "specialized-web-search");
            thread.setDaemon(true);
            return thread;
        });
        List<Future<ProviderOutcome>> futures = new ArrayList<>();
        for (RoutedSearchProvider provider : selected) {
            futures.add(executor.submit(() -> searchProvider(provider, request)));
        }

        long deadline = System.nanoTime() + timeout.toNanos();
        List<SearchResult> results = new ArrayList<>();
        List<String> attempted = new ArrayList<>();
        List<String> cachedProviders = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        try {
            for (int index = 0; index < selected.size(); index++) {
                RoutedSearchProvider provider = selected.get(index);
                attempted.add(provider.name());
                Future<ProviderOutcome> future = futures.get(index);
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    future.cancel(true);
                    notes.add(provider.name() + ": specialized search deadline exceeded");
                    continue;
                }
                try {
                    ProviderOutcome outcome = future.get(remaining, TimeUnit.NANOSECONDS);
                    results.addAll(outcome.results());
                    if (outcome.cached()) cachedProviders.add(provider.name());
                    if (!outcome.error().isBlank()) notes.add(provider.name() + ": " + outcome.error());
                    else if (outcome.results().isEmpty()) notes.add(provider.name() + ": no results");
                } catch (Exception error) {
                    future.cancel(true);
                    notes.add(provider.name() + ": " + usefulMessage(error));
                }
            }
        } finally {
            executor.shutdownNow();
        }
        return new SearchOutcome(intentValues(intents), List.copyOf(attempted),
                List.copyOf(cachedProviders), List.copyOf(notes), List.copyOf(results));
    }

    private ProviderOutcome searchProvider(RoutedSearchProvider provider, SearchRequest request) {
        String key = provider.name() + "|" + request.cacheKey();
        CacheEntry cached = cache.get(key);
        if (cached != null && cached.expiresAt().isAfter(Instant.now())) {
            return new ProviderOutcome(cached.results(), true, "");
        }
        try {
            acquireRateLimit(provider.name());
            List<SearchResult> results = provider.search(request);
            if (!results.isEmpty()) cache(key, provider.name(), results);
            return new ProviderOutcome(results, false, "");
        } catch (Exception error) {
            return new ProviderOutcome(List.of(), false, usefulMessage(error));
        }
    }

    private void acquireRateLimit(String provider) throws InterruptedException {
        Duration minimum = rateLimits.getOrDefault(provider, Duration.ZERO);
        if (minimum.isZero() || minimum.isNegative()) return;
        Object lock = rateLocks.computeIfAbsent(provider, ignored -> new Object());
        synchronized (lock) {
            Instant now = Instant.now();
            Instant allowed = lastCalls.getOrDefault(provider, Instant.EPOCH).plus(minimum);
            long waitMillis = Duration.between(now, allowed).toMillis();
            if (waitMillis > 0) Thread.sleep(waitMillis);
            lastCalls.put(provider, Instant.now());
        }
    }

    private void cache(String key, String provider, List<SearchResult> results) {
        Duration ttl = cacheTtls.getOrDefault(provider, Duration.ZERO);
        if (ttl.isZero() || ttl.isNegative() || cacheMaxEntries == 0) return;
        Instant now = Instant.now();
        cache.entrySet().removeIf(entry -> entry.getValue().expiresAt().isBefore(now));
        if (cache.size() >= cacheMaxEntries) cache.keySet().stream().findFirst().ifPresent(cache::remove);
        cache.put(key, new CacheEntry(List.copyOf(results), now.plus(ttl)));
    }

    private static List<RoutedSearchProvider> buildProviders(ObjectMapper mapper, Environment environment) {
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        WebHttpClient http = new WebHttpClient(client);
        int defaultAttempts = integer(environment, "web.specialized.max-attempts", 2);
        long defaultRetry = longValue(environment, "web.specialized.retry-delay-millis", 500);
        int defaultBytes = integer(environment, "web.specialized.max-response-bytes", 2_097_152);
        List<RoutedSearchProvider> providers = new ArrayList<>();

        String mediaWiki = endpoint(environment, "mediawiki", "https://en.wikipedia.org/w/api.php");
        if (!mediaWiki.isBlank()) providers.add(new MediaWikiSearchProvider(mediaWiki, http, mapper,
                attempts(environment, "mediawiki", defaultAttempts), retry(environment, "mediawiki", defaultRetry),
                bytes(environment, "mediawiki", defaultBytes)));
        String wikidata = endpoint(environment, "wikidata", "https://www.wikidata.org/w/api.php");
        if (!wikidata.isBlank()) providers.add(new WikidataSearchProvider(wikidata, http, mapper,
                attempts(environment, "wikidata", defaultAttempts), retry(environment, "wikidata", defaultRetry),
                bytes(environment, "wikidata", defaultBytes)));
        String arxiv = endpoint(environment, "arxiv", "https://export.arxiv.org/api/query");
        if (!arxiv.isBlank()) providers.add(new ArxivSearchProvider(arxiv, http,
                attempts(environment, "arxiv", defaultAttempts), retry(environment, "arxiv", defaultRetry),
                bytes(environment, "arxiv", defaultBytes)));
        String gdelt = endpoint(environment, "gdelt", "https://api.gdeltproject.org/api/v2/doc/doc");
        if (!gdelt.isBlank()) providers.add(new GdeltSearchProvider(gdelt, http, mapper,
                attempts(environment, "gdelt", defaultAttempts), retry(environment, "gdelt", defaultRetry),
                bytes(environment, "gdelt", defaultBytes)));
        String commonCrawl = endpoint(environment, "commoncrawl", "https://index.commoncrawl.org/collinfo.json");
        if (!commonCrawl.isBlank()) providers.add(new CommonCrawlSearchProvider(commonCrawl, http, mapper,
                attempts(environment, "commoncrawl", defaultAttempts), retry(environment, "commoncrawl", defaultRetry),
                bytes(environment, "commoncrawl", defaultBytes)));
        return List.copyOf(providers);
    }

    private static Map<String, Duration> cacheTtls(Environment environment) {
        Map<String, Duration> values = new LinkedHashMap<>();
        values.put("mediawiki", seconds(environment, "web.specialized.mediawiki.cache-ttl-seconds", 3600));
        values.put("wikidata", seconds(environment, "web.specialized.wikidata.cache-ttl-seconds", 3600));
        values.put("arxiv", seconds(environment, "web.specialized.arxiv.cache-ttl-seconds", 1800));
        values.put("gdelt", seconds(environment, "web.specialized.gdelt.cache-ttl-seconds", 300));
        values.put("commoncrawl", seconds(environment, "web.specialized.commoncrawl.cache-ttl-seconds", 3600));
        return values;
    }

    private static Map<String, Duration> rateLimits(Environment environment) {
        Map<String, Duration> values = new LinkedHashMap<>();
        values.put("mediawiki", millis(environment, "web.specialized.mediawiki.min-interval-millis", 100));
        values.put("wikidata", millis(environment, "web.specialized.wikidata.min-interval-millis", 100));
        values.put("arxiv", millis(environment, "web.specialized.arxiv.min-interval-millis", 3000));
        values.put("gdelt", millis(environment, "web.specialized.gdelt.min-interval-millis", 1000));
        values.put("commoncrawl", millis(environment, "web.specialized.commoncrawl.min-interval-millis", 500));
        return values;
    }

    private static List<String> intentValues(List<SearchIntent> intents) {
        return intents.stream().map(SearchIntent::value).toList();
    }

    private static String endpoint(Environment environment, String provider, String fallback) {
        return environment.getProperty("web.specialized." + provider + ".url", fallback).trim();
    }

    private static int attempts(Environment environment, String provider, int fallback) {
        return integer(environment, "web.specialized." + provider + ".max-attempts", fallback);
    }

    private static long retry(Environment environment, String provider, long fallback) {
        return longValue(environment, "web.specialized." + provider + ".retry-delay-millis", fallback);
    }

    private static int bytes(Environment environment, String provider, int fallback) {
        return integer(environment, "web.specialized." + provider + ".max-response-bytes", fallback);
    }

    private static Duration seconds(Environment environment, String key, long fallback) {
        return Duration.ofSeconds(longValue(environment, key, fallback));
    }

    private static Duration millis(Environment environment, String key, long fallback) {
        return Duration.ofMillis(longValue(environment, key, fallback));
    }

    private static int integer(Environment environment, String key, int fallback) {
        return environment.getProperty(key, Integer.class, fallback);
    }

    private static long longValue(Environment environment, String key, long fallback) {
        return environment.getProperty(key, Long.class, fallback);
    }

    private static String usefulMessage(Exception error) {
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }

    record SearchOutcome(List<String> intents, List<String> attemptedProviders,
                         List<String> cachedProviders, List<String> notes,
                         List<SearchResult> results) {
        SearchOutcome(List<String> intents, List<String> attemptedProviders,
                      List<String> cachedProviders, List<SearchResult> results) {
            this(intents, attemptedProviders, cachedProviders, List.of(), results);
        }
    }

    private record ProviderOutcome(List<SearchResult> results, boolean cached, String error) {}

    private record CacheEntry(List<SearchResult> results, Instant expiresAt) {}
}
