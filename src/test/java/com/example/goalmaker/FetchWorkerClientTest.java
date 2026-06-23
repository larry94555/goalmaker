package com.example.goalmaker;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FetchWorkerClientTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void reusesHealthyWorkerAndRecoversAfterCrashTimeoutAndOversizedOutput() throws Exception {
        FetchWorkerClient client = new FetchWorkerClient(mapper);
        FetchIsolationSettings isolation = new FetchIsolationSettings(
                1, 64, 1, 3, 1_024, FakeFetchWorkerMain.class.getName());
        try {
            Map<String, Object> first = client.fetch(Map.of("url", "https://example.com/one"),
                    fetchSettings(), isolation);
            Map<String, Object> second = client.fetch(Map.of("url", "https://example.com/two"),
                    fetchSettings(), isolation);
            assertEquals(first.get("worker_pid"), second.get("worker_pid"));
            assertEquals(1, client.workerCount());

            assertThrows(IllegalStateException.class, () -> client.fetch(
                    Map.of("url", "https://example.com/crash"), fetchSettings(), isolation));
            Map<String, Object> afterCrash = client.fetch(Map.of("url", "https://example.com/recovered"),
                    fetchSettings(), isolation);
            assertNotEquals(first.get("worker_pid"), afterCrash.get("worker_pid"));

            IllegalStateException timeout = assertThrows(IllegalStateException.class, () -> client.fetch(
                    Map.of("url", "https://example.com/hang"), fetchSettings(), isolation));
            assertTrue(timeout.getMessage().contains("wall-clock"));
            Map<String, Object> afterTimeout = client.fetch(Map.of("url", "https://example.com/recovered-again"),
                    fetchSettings(), isolation);
            assertEquals("worker response", afterTimeout.get("content"));

            IllegalStateException oversized = assertThrows(IllegalStateException.class, () -> client.fetch(
                    Map.of("url", "https://example.com/large"), fetchSettings(), isolation));
            assertTrue(oversized.getMessage().contains("output exceeded size limit"));
            Map<String, Object> finalResponse = client.fetch(Map.of("url", "https://example.com/final"),
                    fetchSettings(), isolation);
            assertEquals("worker response", finalResponse.get("content"));
            Map<String, Object> status = client.status(isolation);
            assertEquals("process", status.get("mode"));
            assertEquals(1, ((Number) status.get("live_workers")).intValue());
            assertTrue(((Number) status.get("workers_started")).longValue() >= 4);
            assertEquals(3L, ((Number) status.get("worker_failures")).longValue());
            assertTrue(String.valueOf(status.get("last_worker_failure")).contains("output exceeded"));
        } finally {
            client.close();
        }
        assertEquals(0, client.workerCount());
    }

    private static FetchSettings fetchSettings() {
        return new FetchSettings(1, 0, 1_024, 1, 1_000, false, Set.of(80, 443),
                5, 4, false, 1, 1, 0, 1_024, 1, 0, 0, 1, 1);
    }
}
