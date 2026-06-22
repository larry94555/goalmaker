package com.example.goalmaker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebSearchToolProviderTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void prefersSearxngRetriesDeduplicatesAndCaches() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<String> query = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/search", exchange -> {
            query.set(exchange.getRequestURI().getRawQuery());
            if (calls.incrementAndGet() == 1) {
                send(exchange, 503, "text/plain", "retry");
                return;
            }
            send(exchange, 200, "application/json", """
                    {"results":[
                      {"title":"First","url":"https://example.com/a#part","content":"One","engine":"alpha"},
                      {"title":"Duplicate","url":"https://example.com/a","content":"Same","engine":"beta"},
                      {"title":"Second","url":"https://example.org/b","content":"Two",
                       "publishedDate":"2026-06-22","engine":"gamma"}
                    ]}
                    """);
        });
        server.start();
        try {
            WebSearchToolProvider provider = provider(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/search", "");
            ReflectionTestUtils.setField(provider, "maxAttempts", 2);
            ReflectionTestUtils.setField(provider, "retryDelayMillis", 0L);
            ToolCatalog catalog = catalog(provider);

            Map<String, Object> arguments = Map.of(
                    "query", "current answer",
                    "max_results", 3,
                    "language", "en-US",
                    "time_range", "month",
                    "page", 2,
                    "safe_search", 2,
                    "categories", java.util.List.of("news"));
            JsonNode first = payload(catalog.execute("web_search", arguments));
            JsonNode second = payload(catalog.execute("web_search", arguments));

            assertEquals(2, calls.get());
            assertTrue(query.get().contains("q=current+answer"));
            assertTrue(query.get().contains("format=json"));
            assertTrue(query.get().contains("language=en-US"));
            assertTrue(query.get().contains("time_range=month"));
            assertTrue(query.get().contains("pageno=2"));
            assertTrue(query.get().contains("safesearch=2"));
            assertTrue(query.get().contains("categories=news"));
            assertEquals("searxng", first.path("provider").asText());
            assertEquals(2, first.path("result_count").asInt());
            assertEquals("https://example.com/a#part", first.path("results").path(0).path("url").asText());
            assertEquals("2026-06-22", first.path("results").path(1).path("published_at").asText());
            assertFalse(first.path("cached").asBoolean());
            assertTrue(second.path("cached").asBoolean());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void fallsBackToDuckDuckGoAndReturnsProviderDiagnostics() throws Exception {
        AtomicReference<String> duckQuery = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/searx", exchange -> send(exchange, 503, "text/plain", "unavailable"));
        server.createContext("/ddg", exchange -> {
            duckQuery.set(exchange.getRequestURI().getRawQuery());
            send(exchange, 200, "text/html", searchResults(7));
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            WebSearchToolProvider provider = provider(base + "/searx", base + "/ddg");
            ReflectionTestUtils.setField(provider, "maxAttempts", 1);
            ToolCatalog catalog = catalog(provider);

            String wrapped = catalog.execute("web_search", Map.of(
                    "query", "capital of France", "max_results", 6,
                    "language", "us-en", "time_range", "day"));
            JsonNode result = payload(wrapped);

            assertTrue(wrapped.startsWith("[UNTRUSTED CONTENT from web_search"));
            assertEquals("duckduckgo", result.path("provider").asText());
            assertEquals("searxng", result.path("providers_attempted").path(0).asText());
            assertEquals("duckduckgo", result.path("providers_attempted").path(1).asText());
            assertTrue(result.path("provider_notes").path(0).asText().contains("HTTP 503"));
            assertEquals(6, result.path("result_count").asInt());
            assertEquals("https://example.com/page/1?source=test",
                    result.path("results").path(0).path("url").asText());
            assertEquals("q=capital+of+France&kl=us-en&df=d&kp=-1", duckQuery.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsMissingQuery() {
        WebSearchToolProvider provider = provider("", "");
        String result = catalog(provider).execute("web_search", Map.of());
        assertEquals("ERROR: query is required", result);
    }

    private WebSearchToolProvider provider(String searxng, String duckDuckGo) {
        WebSearchToolProvider provider = new WebSearchToolProvider(mapper);
        ReflectionTestUtils.setField(provider, "searxngUrl", searxng);
        ReflectionTestUtils.setField(provider, "duckDuckGoUrl", duckDuckGo);
        ReflectionTestUtils.setField(provider, "retryDelayMillis", 0L);
        return provider;
    }

    private ToolCatalog catalog(WebSearchToolProvider provider) {
        ToolCatalog catalog = new ToolCatalog(
                new SkillToolProvider(mapper), new McpToolProvider(mapper), provider, null);
        catalog.refresh();
        return catalog;
    }

    private JsonNode payload(String wrapped) throws Exception {
        int start = wrapped.indexOf('\n') + 1;
        int end = wrapped.lastIndexOf("\n[END UNTRUSTED CONTENT]");
        return mapper.readTree(wrapped.substring(start, end));
    }

    private static String searchResults(int count) {
        StringBuilder html = new StringBuilder("<html><body>");
        for (int i = 1; i <= count; i++) {
            String destination = "https%3A%2F%2Fexample.com%2Fpage%2F" + i + "%3Fsource%3Dtest";
            html.append("<div class='result'><a class='result__a' href='/l/?uddg=")
                    .append(destination).append("&amp;rut=value'>Result ").append(i)
                    .append("</a><div class='result__snippet'>Snippet ").append(i)
                    .append("</div></div>");
        }
        return html.append("</body></html>").toString();
    }

    private static void send(HttpExchange exchange, int status, String contentType, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", contentType + "; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
