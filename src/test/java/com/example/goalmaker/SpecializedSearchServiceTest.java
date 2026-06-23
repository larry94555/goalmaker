package com.example.goalmaker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpecializedSearchServiceTest {
    @Test
    void routesOnlyMatchingProvidersAndCachesTheirResults() {
        FakeProvider mediaWiki = provider("mediawiki", SearchIntent.FACTUAL_ENTITY,
                "https://en.wikipedia.org/wiki/France");
        FakeProvider wikidata = provider("wikidata", SearchIntent.FACTUAL_ENTITY,
                "https://www.wikidata.org/wiki/Q142");
        FakeProvider gdelt = provider("gdelt", SearchIntent.CURRENT_NEWS,
                "https://news.example/france");
        SpecializedSearchService service = service(List.of(mediaWiki, wikidata, gdelt));
        SearchRequest request = request("What is the capital of France?");

        SpecializedSearchService.SearchOutcome first = service.search(request);
        SpecializedSearchService.SearchOutcome second = service.search(request);

        assertEquals(List.of("general", "factual-entity"), first.intents());
        assertEquals(List.of("mediawiki", "wikidata"), first.attemptedProviders());
        assertEquals(2, first.results().size());
        assertTrue(second.cachedProviders().containsAll(List.of("mediawiki", "wikidata")));
        assertEquals(1, mediaWiki.calls.get());
        assertEquals(1, wikidata.calls.get());
        assertEquals(0, gdelt.calls.get());
    }

    @Test
    void preservesSuccessfulProvidersWhenAnotherProviderFails() {
        FakeProvider mediaWiki = provider("mediawiki", SearchIntent.FACTUAL_ENTITY,
                "https://en.wikipedia.org/wiki/France");
        FakeProvider wikidata = new FakeProvider("wikidata", SearchIntent.FACTUAL_ENTITY,
                "", true);

        SpecializedSearchService.SearchOutcome outcome = service(List.of(mediaWiki, wikidata))
                .search(request("What is France?"));

        assertEquals(1, outcome.results().size());
        assertTrue(outcome.notes().get(0).contains("wikidata: test failure"));
    }

    @Test
    void webSearchBlendsSpecializedAndGeneralResultsWithProvenance() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/search", exchange -> send(exchange, """
                {"results":[{"title":"General result","url":"https://general.example/france",
                "content":"General source","engine":"general-engine"}]}
                """));
        server.start();
        try {
            FakeProvider mediaWiki = provider("mediawiki", SearchIntent.FACTUAL_ENTITY,
                    "https://en.wikipedia.org/wiki/France");
            FakeProvider wikidata = provider("wikidata", SearchIntent.FACTUAL_ENTITY,
                    "https://www.wikidata.org/wiki/Q142");
            ObjectMapper mapper = new ObjectMapper();
            String endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/search";
            WebSearchToolProvider baseline = new WebSearchToolProvider(mapper, HttpClient.newHttpClient());
            configureGeneralSearch(baseline, endpoint);
            Map<String, Object> baselinePayload = baseline.searchPayload(Map.of(
                    "query", "What is the capital of France?", "max_results", 5));

            WebSearchToolProvider search = new WebSearchToolProvider(
                    mapper, service(List.of(mediaWiki, wikidata)), HttpClient.newHttpClient());
            configureGeneralSearch(search, endpoint);

            Map<String, Object> payload = search.searchPayload(Map.of(
                    "query", "What is the capital of France?", "max_results", 5));
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> baselineResults =
                    (List<Map<String, Object>>) baselinePayload.get("results");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> results = (List<Map<String, Object>>) payload.get("results");

            assertEquals("blended", payload.get("provider"));
            assertEquals(List.of("general", "factual-entity"), payload.get("query_intents"));
            assertEquals(List.of("searxng", "mediawiki", "wikidata"), payload.get("providers_attempted"));
            assertEquals(List.of("mediawiki", "wikidata"), payload.get("specialized_providers_attempted"));
            assertEquals(List.of("mediawiki", "wikidata", "searxng"),
                    results.stream().map(item -> String.valueOf(item.get("provider"))).toList());
            assertEquals(1, baselineResults.stream()
                    .map(item -> String.valueOf(item.get("provider"))).distinct().count());
            assertEquals(3, results.stream()
                    .map(item -> String.valueOf(item.get("provider"))).distinct().count());
            assertTrue(results.size() > baselineResults.size());
            assertFalse((Boolean) payload.get("cached"));
        } finally {
            server.stop(0);
        }
    }

    private static SpecializedSearchService service(List<RoutedSearchProvider> providers) {
        Map<String, Duration> cache = providers.stream().collect(java.util.stream.Collectors.toMap(
                SearchProvider::name, ignored -> Duration.ofMinutes(5)));
        return new SpecializedSearchService(providers, cache, Map.of(),
                20, 4, Duration.ofSeconds(5));
    }

    private static void configureGeneralSearch(WebSearchToolProvider search, String endpoint) {
        ReflectionTestUtils.setField(search, "searxngUrl", endpoint);
        ReflectionTestUtils.setField(search, "duckDuckGoUrl", "");
        ReflectionTestUtils.setField(search, "duckDuckGoLiteUrl", "");
        ReflectionTestUtils.setField(search, "maxAttempts", 1);
    }

    private static FakeProvider provider(String name, SearchIntent intent, String url) {
        return new FakeProvider(name, intent, url, false);
    }

    private static SearchRequest request(String query) {
        return new SearchRequest(query, 5, "auto", "", 1, 1, List.of());
    }

    private static void send(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static final class FakeProvider implements RoutedSearchProvider {
        private final String name;
        private final SearchIntent intent;
        private final String url;
        private final boolean fails;
        private final AtomicInteger calls = new AtomicInteger();

        private FakeProvider(String name, SearchIntent intent, String url, boolean fails) {
            this.name = name;
            this.intent = intent;
            this.url = url;
            this.fails = fails;
        }

        @Override
        public SearchIntent intent() {
            return intent;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public List<SearchResult> search(SearchRequest request) {
            calls.incrementAndGet();
            if (fails) throw new IllegalStateException("test failure");
            return List.of(new SearchResult(name + " result", url, name + " snippet", "", name, name));
        }
    }
}
