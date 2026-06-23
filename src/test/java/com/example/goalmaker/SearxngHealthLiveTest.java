package com.example.goalmaker;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SearxngHealthLiveTest {
    @Test
    @EnabledIfSystemProperty(named = "goalmaker.live-web-test", matches = "true")
    void probesLocalSearxngAndAllowsSearch() {
        SearxngHealthManager.Config config = new SearxngHealthManager.Config(
                "http://127.0.0.1:8888/search", false,
                Duration.ofSeconds(30), Duration.ofSeconds(5), 2,
                Duration.ofSeconds(30), Duration.ofMillis(1500),
                Duration.ofSeconds(10), Duration.ofSeconds(1),
                "docker", "docker-compose.searxng.yml", "searxng-compose.log", Duration.ofSeconds(30));
        SearxngHealthManager manager = new SearxngHealthManager(config,
                new SearxngHealthManager.HttpProbe(new ObjectMapper(), HttpClient.newHttpClient()),
                ignored -> SearxngHealthManager.StartResult.failed("not used"), Clock.systemUTC());

        SearxngHealthManager.ProbeResult result = manager.probeNow();

        assertTrue(result.success());
        assertTrue(java.util.Set.of("healthy", "degraded").contains(manager.snapshot().status()));
        assertTrue(manager.acquireSearchPermission().allowed());
    }
}
