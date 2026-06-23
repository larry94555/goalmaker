package com.example.goalmaker;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebResearchLiveTest {
    @Test
    @EnabledIfSystemProperty(named = "goalmaker.live-web-test", matches = "true")
    void researchesThroughLocalSearxngAndFetchesPublicEvidence() {
        ObjectMapper mapper = new ObjectMapper();
        WebSearchToolProvider search = new WebSearchToolProvider(mapper);
        ReflectionTestUtils.setField(search, "searxngUrl", "http://127.0.0.1:8888/search");
        ReflectionTestUtils.setField(search, "duckDuckGoUrl", "");
        ReflectionTestUtils.setField(search, "maxAttempts", 1);
        WebFetchToolProvider fetch = new WebFetchToolProvider(mapper);
        WebResearchToolProvider research = new WebResearchToolProvider(mapper, search, fetch);
        ReflectionTestUtils.setField(research, "timeoutSeconds", 60);

        WebResearchToolProvider.ResearchRequest request = WebResearchToolProvider.ResearchRequest.from(
                Map.of("query", "Spring Boot official documentation", "max_sources", 2, "min_sources", 1),
                2, 1);
        Map<String, Object> payload = research.researchPayload(request);

        assertEquals("searxng", payload.get("search_provider"));
        @SuppressWarnings("unchecked")
        Map<String, Object> corroboration = (Map<String, Object>) payload.get("corroboration");
        assertEquals(true, corroboration.get("source_threshold_met"));
        assertTrue(((Number) corroboration.get("evidence_sources")).intValue() >= 1);
        assertFalse(((java.util.List<?>) payload.get("evidence")).isEmpty());
    }
}
