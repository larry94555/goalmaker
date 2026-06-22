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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebFetchToolProviderTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void followsValidatedRedirectAndExtractsMainContent() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/start", exchange -> {
            exchange.getResponseHeaders().add("Location", "/article");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/article", exchange -> send(exchange, 200, "text/html", """
                <html><head><title>Useful source</title><script>ignore()</script></head>
                <body><nav>Navigation</nav><main><h1>Answer</h1><p>Supported evidence.</p></main></body></html>
                """));
        server.start();
        try {
            WebFetchToolProvider provider = new WebFetchToolProvider(mapper);
            ReflectionTestUtils.setField(provider, "allowPrivateAddresses", true);
            ReflectionTestUtils.setField(provider, "retryDelayMillis", 0L);
            ToolCatalog catalog = catalog(provider);
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/start";

            String wrapped = catalog.execute("web_fetch", Map.of("url", url));
            JsonNode result = payload(wrapped);

            assertTrue(wrapped.startsWith("[UNTRUSTED CONTENT from web_fetch"));
            assertEquals("Useful source", result.path("title").asText());
            assertTrue(result.path("url").asText().endsWith("/article"));
            assertEquals("Answer Supported evidence.", result.path("content").asText());
            assertFalse(result.path("truncated").asBoolean());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void truncatesLargeReadablePage() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/large", exchange -> send(exchange, 200, "text/plain", "x".repeat(1_500)));
        server.start();
        try {
            WebFetchToolProvider provider = new WebFetchToolProvider(mapper);
            ReflectionTestUtils.setField(provider, "allowPrivateAddresses", true);
            ToolCatalog catalog = catalog(provider);
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/large";

            JsonNode result = payload(catalog.execute("web_fetch", Map.of("url", url, "max_chars", 1_000)));

            assertEquals(1_000, result.path("content").asText().length());
            assertTrue(result.path("truncated").asBoolean());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void blocksPrivateNetworkTargetsByDefault() {
        WebFetchToolProvider provider = new WebFetchToolProvider(mapper);
        String result = catalog(provider).execute("web_fetch", Map.of("url", "http://127.0.0.1/private"));
        assertEquals("ERROR: url resolves to a private or local network address", result);
    }

    private ToolCatalog catalog(WebFetchToolProvider provider) {
        ToolCatalog catalog = new ToolCatalog(
                new SkillToolProvider(mapper), new McpToolProvider(mapper), null, provider);
        catalog.refresh();
        return catalog;
    }

    private JsonNode payload(String wrapped) throws Exception {
        int start = wrapped.indexOf('\n') + 1;
        int end = wrapped.lastIndexOf("\n[END UNTRUSTED CONTENT]");
        return mapper.readTree(wrapped.substring(start, end));
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
