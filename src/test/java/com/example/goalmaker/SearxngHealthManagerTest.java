package com.example.goalmaker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SearxngHealthManagerTest {
    @Test
    void reportsDisabledWhenNoEndpointIsConfigured() {
        SearxngHealthManager manager = manager(config("", false), ignored ->
                SearxngHealthManager.ProbeResult.success(1), new MutableClock());

        assertEquals("disabled", manager.snapshot().status());
        assertNull(manager.probeNow());
        assertFalse(manager.acquireSearchPermission().allowed());
        assertEquals(1, manager.snapshot().skippedSearches());
    }

    @Test
    void transitionsAcrossHealthyDegradedUnavailableHalfOpenAndRecovered() {
        Queue<SearxngHealthManager.ProbeResult> results = new ArrayDeque<>();
        results.add(SearxngHealthManager.ProbeResult.success(10));
        results.add(SearxngHealthManager.ProbeResult.success(150));
        results.add(SearxngHealthManager.ProbeResult.failure(5, "malformed JSON"));
        results.add(SearxngHealthManager.ProbeResult.failure(5, "connection refused"));
        results.add(SearxngHealthManager.ProbeResult.success(12));
        MutableClock clock = new MutableClock();
        SearxngHealthManager manager = manager(config("http://searx.test/search", false),
                ignored -> results.remove(), clock);

        manager.probeNow();
        assertEquals("healthy", manager.snapshot().status());
        manager.probeNow();
        assertEquals("degraded", manager.snapshot().status());
        manager.probeNow();
        assertEquals("degraded", manager.snapshot().status());
        manager.probeNow();
        assertEquals("unavailable", manager.snapshot().status());
        assertFalse(manager.acquireSearchPermission().allowed());

        clock.advance(Duration.ofSeconds(31));
        assertTrue(manager.acquireSearchPermission().allowed());
        assertFalse(manager.acquireSearchPermission().allowed());
        manager.recordSearchFailure("half-open request failed");
        assertEquals("unavailable", manager.snapshot().status());

        manager.probeNow();
        assertEquals("healthy", manager.snapshot().status());
        assertTrue(manager.acquireSearchPermission().allowed());
        assertEquals(5, manager.snapshot().totalProbes());
        assertEquals(3, manager.snapshot().successfulProbes());
        assertEquals(2, manager.snapshot().failedProbes());
        assertEquals(1, manager.snapshot().searchFailures());
    }

    @Test
    void managedInitializationStartsComposeAndWaitsForReadiness() {
        Queue<SearxngHealthManager.ProbeResult> results = new ArrayDeque<>();
        results.add(SearxngHealthManager.ProbeResult.failure(1, "not running"));
        results.add(SearxngHealthManager.ProbeResult.success(5));
        AtomicInteger starts = new AtomicInteger();
        SearxngHealthManager.Config config = config("http://searx.test/search", true);
        SearxngHealthManager manager = new SearxngHealthManager(config,
                ignored -> results.remove(), ignored -> {
                    starts.incrementAndGet();
                    return SearxngHealthManager.StartResult.started();
                }, new MutableClock());

        manager.initialize();

        assertEquals(1, starts.get());
        assertEquals("healthy", manager.snapshot().status());
        assertEquals(2, manager.snapshot().totalProbes());
    }

    @Test
    void httpProbeRecognizesValidSlowAndMalformedResponses() throws Exception {
        AtomicInteger mode = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/config", exchange -> {
            if (mode.get() == 1) sleep(80);
            send(exchange, mode.get() == 2 ? "{}" : "{\"engines\":[]}");
        });
        server.start();
        try {
            SearxngHealthManager.Config config = config(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/search", false);
            SearxngHealthManager.HttpProbe probe = new SearxngHealthManager.HttpProbe(
                    new ObjectMapper(), HttpClient.newHttpClient());
            probe.probe(config);
            SearxngHealthManager manager = manager(config, probe, new MutableClock());

            manager.probeNow();
            assertEquals("healthy", manager.snapshot().status());
            mode.set(1);
            manager.probeNow();
            assertEquals("degraded", manager.snapshot().status());
            mode.set(2);
            SearxngHealthManager.ProbeResult malformed = manager.probeNow();
            assertFalse(malformed.success());
            assertTrue(malformed.error().contains("not valid config JSON"));
        } finally {
            server.stop(0);
        }
    }

    private static SearxngHealthManager manager(SearxngHealthManager.Config config,
                                                 SearxngHealthManager.Probe probe,
                                                 Clock clock) {
        return new SearxngHealthManager(config, probe,
                ignored -> SearxngHealthManager.StartResult.failed("not expected"), clock);
    }

    private static SearxngHealthManager.Config config(String endpoint, boolean manage) {
        return new SearxngHealthManager.Config(
                endpoint, manage, Duration.ofSeconds(30), Duration.ofSeconds(1), 2,
                Duration.ofSeconds(30), Duration.ofMillis(50),
                Duration.ofMillis(200), Duration.ofMillis(1),
                "docker", "docker-compose.searxng.yml", "searxng-compose.log", Duration.ofSeconds(1));
    }

    private static void send(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
    }

    static final class MutableClock extends Clock {
        private Instant current = Instant.parse("2026-06-22T00:00:00Z");

        void advance(Duration duration) {
            current = current.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return current;
        }
    }
}
