package com.example.goalmaker;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Duration;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class WebSearchHealthControllerTest {
    @Test
    void returnsStartingAndUnavailableHttpStatesWithMetrics() throws Exception {
        SearxngHealthManager starting = manager("http://searx.test/search");
        MockMvc startingMvc = MockMvcBuilders
                .standaloneSetup(new WebSearchHealthController(starting)).build();
        startingMvc.perform(get("/health/web-search"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("starting"))
                .andExpect(jsonPath("$.fallbacks_remain_available").value(true));

        starting.probeNow();
        startingMvc.perform(get("/health/web-search"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("unavailable"))
                .andExpect(jsonPath("$.recent_failure").value("connection refused"))
                .andExpect(jsonPath("$.metrics.failed_probes").value(1));
    }

    @Test
    void disabledProviderIsAnIntentionalHealthyConfiguration() throws Exception {
        SearxngHealthManager disabled = manager("");
        MockMvc mvc = MockMvcBuilders
                .standaloneSetup(new WebSearchHealthController(disabled)).build();

        mvc.perform(get("/health/web-search"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("disabled"))
                .andExpect(jsonPath("$.configured").value(false));
    }

    private static SearxngHealthManager manager(String endpoint) {
        SearxngHealthManager.Config config = new SearxngHealthManager.Config(
                endpoint, false, Duration.ofSeconds(30), Duration.ofSeconds(1), 2,
                Duration.ofSeconds(30), Duration.ofMillis(100),
                Duration.ofSeconds(1), Duration.ofMillis(1),
                "docker", "compose.yml", "compose.log", Duration.ofSeconds(1));
        return new SearxngHealthManager(config,
                ignored -> SearxngHealthManager.ProbeResult.failure(1, "connection refused"),
                ignored -> SearxngHealthManager.StartResult.failed("not expected"),
                new SearxngHealthManagerTest.MutableClock());
    }
}
