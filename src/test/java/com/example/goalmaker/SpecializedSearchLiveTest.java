package com.example.goalmaker;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpecializedSearchLiveTest {
    @Test
    @EnabledIfSystemProperty(named = "goalmaker.live-web-test", matches = "true")
    void queriesTokenFreeStructuredProviders() {
        SpecializedSearchService service = new SpecializedSearchService(
                new ObjectMapper(), new MockEnvironment());

        SpecializedSearchService.SearchOutcome entity = service.search(request(
                "What is the capital of France?"));
        SpecializedSearchService.SearchOutcome scholarly = service.search(request(
                "Find research papers about quantum error correction"));
        SpecializedSearchService.SearchOutcome archival = service.search(request(
                "Find archived versions of https://example.com/"));

        assertTrue(entity.attemptedProviders().containsAll(List.of("mediawiki", "wikidata")));
        assertFalse(entity.results().isEmpty());
        assertTrue(scholarly.attemptedProviders().contains("arxiv"));
        assertFalse(scholarly.results().isEmpty());
        assertTrue(archival.attemptedProviders().contains("commoncrawl"));
        assertFalse(archival.results().isEmpty());
    }

    private static SearchRequest request(String query) {
        return new SearchRequest(query, 3, "en", "", 1, 1, List.of());
    }
}
