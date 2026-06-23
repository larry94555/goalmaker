package com.example.goalmaker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpecializedSearchProviderTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final WebHttpClient http = new WebHttpClient(HttpClient.newHttpClient());

    @Test
    void parsesMediaWikiAndWikidataResults() throws Exception {
        AtomicReference<String> mediaQuery = new AtomicReference<>();
        AtomicReference<String> dataQuery = new AtomicReference<>();
        HttpServer server = server();
        server.createContext("/mediawiki", exchange -> {
            mediaQuery.set(exchange.getRequestURI().getRawQuery());
            send(exchange, "application/json", """
                    {"query":{"search":[{"title":"France","pageid":123,
                    "snippet":"A <span>country</span> in Europe","timestamp":"2026-06-20T00:00:00Z"}]}}
                    """);
        });
        server.createContext("/wikidata", exchange -> {
            dataQuery.set(exchange.getRequestURI().getRawQuery());
            send(exchange, "application/json", """
                    {"search":[{"id":"Q142","label":"France","description":"country in Western Europe"}]}
                    """);
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            SearchRequest request = request("capital of France", 2);
            List<SearchResult> media = new MediaWikiSearchProvider(
                    base + "/mediawiki", http, mapper, 1, 0, 100_000).search(request);
            List<SearchResult> data = new WikidataSearchProvider(
                    base + "/wikidata", http, mapper, 1, 0, 100_000).search(request);

            assertEquals("France", media.get(0).title());
            assertEquals("A country in Europe", media.get(0).snippet());
            assertTrue(media.get(0).url().endsWith("/?curid=123"));
            assertEquals("wikidata", data.get(0).provider());
            assertEquals("https://www.wikidata.org/wiki/Q142", data.get(0).url());
            assertTrue(mediaQuery.get().contains("srsearch=capital+of+France"));
            assertTrue(dataQuery.get().contains("language=en"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void parsesArxivAtomAndBuildsAConstrainedQuery() throws Exception {
        AtomicReference<String> query = new AtomicReference<>();
        HttpServer server = server();
        server.createContext("/arxiv", exchange -> {
            query.set(exchange.getRequestURI().getRawQuery());
            send(exchange, "application/atom+xml", """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <feed xmlns="http://www.w3.org/2005/Atom">
                      <entry><id>http://arxiv.org/abs/1234.5678</id>
                        <title> Quantum Error Correction </title>
                        <summary>A useful paper.\nWith details.</summary>
                        <published>2026-01-02T03:04:05Z</published>
                        <link href="https://arxiv.org/abs/1234.5678" rel="alternate"/>
                      </entry>
                    </feed>
                    """);
        });
        server.start();
        try {
            String endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/arxiv";
            List<SearchResult> results = new ArxivSearchProvider(endpoint, http, 1, 0, 100_000)
                    .search(request("Find research papers about quantum error correction", 2));

            assertEquals("Quantum Error Correction", results.get(0).title());
            assertEquals("A useful paper. With details.", results.get(0).snippet());
            assertEquals("https://arxiv.org/abs/1234.5678", results.get(0).url());
            assertTrue(query.get().contains("all%3Aquantum+AND+all%3Aerror+AND+all%3Acorrection"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void parsesGdeltArticlesAndAppliesRecency() throws Exception {
        AtomicReference<String> query = new AtomicReference<>();
        HttpServer server = server();
        server.createContext("/gdelt", exchange -> {
            query.set(exchange.getRequestURI().getRawQuery());
            send(exchange, "application/json", """
                    {"articles":[{"url":"https://news.example/article","title":"Current event",
                    "seendate":"20260622T120000Z","domain":"news.example",
                    "sourcecountry":"US","language":"English"}]}
                    """);
        });
        server.start();
        try {
            String endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/gdelt";
            SearchRequest request = new SearchRequest("current event", 2, "auto", "day", 1, 1, List.of());
            List<SearchResult> results = new GdeltSearchProvider(
                    endpoint, http, mapper, 1, 0, 100_000).search(request);

            assertEquals("gdelt", results.get(0).provider());
            assertEquals("2026-06-22T12:00:00Z", results.get(0).publishedAt());
            assertTrue(query.get().contains("timespan=24h"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void discoversLatestCommonCrawlIndexAndParsesCaptures() throws Exception {
        AtomicReference<String> indexQuery = new AtomicReference<>();
        HttpServer server = server();
        server.createContext("/collections", exchange -> send(exchange, "application/json", """
                [{"id":"CC-MAIN-TEST","cdx-api":"http://127.0.0.1:%d/index"}]
                """.formatted(server.getAddress().getPort())));
        server.createContext("/index", exchange -> {
            indexQuery.set(exchange.getRequestURI().getRawQuery());
            send(exchange, "application/x-ndjson",
                    "{\"url\":\"https://example.com/old\",\"timestamp\":\"20260620112233\","
                            + "\"mime\":\"text/html\",\"status\":\"200\",\"digest\":\"ABC\"}\n");
        });
        server.start();
        try {
            String endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/collections";
            List<SearchResult> results = new CommonCrawlSearchProvider(
                    endpoint, http, mapper, 1, 0, 100_000)
                    .search(request("Find archived versions of https://example.com/old", 2));

            assertEquals("commoncrawl", results.get(0).provider());
            assertEquals("CC-MAIN-TEST", results.get(0).engine());
            assertEquals("2026-06-20T11:22:33Z", results.get(0).publishedAt());
            assertTrue(indexQuery.get().contains("url=https%3A%2F%2Fexample.com%2Fold"));
            assertTrue(indexQuery.get().contains("collapse=digest"));
        } finally {
            server.stop(0);
        }
    }

    private static SearchRequest request(String query, int maximum) {
        return new SearchRequest(query, maximum, "en", "", 1, 1, List.of());
    }

    private static HttpServer server() throws IOException {
        return HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    }

    private static void send(HttpExchange exchange, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", contentType + "; charset=utf-8");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
