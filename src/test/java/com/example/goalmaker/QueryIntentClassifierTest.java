package com.example.goalmaker;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class QueryIntentClassifierTest {
    @Test
    void classifiesNewsScholarlyEntityAndArchiveQueriesDeterministically() {
        assertEquals(List.of(SearchIntent.GENERAL, SearchIntent.CURRENT_NEWS),
                classify("What are today's technology headlines?", "", List.of()));
        assertEquals(List.of(SearchIntent.GENERAL, SearchIntent.SCHOLARLY),
                classify("Find research papers about quantum error correction", "", List.of()));
        assertEquals(List.of(SearchIntent.GENERAL, SearchIntent.FACTUAL_ENTITY),
                classify("What is the capital of France?", "", List.of()));
        assertEquals(List.of(SearchIntent.GENERAL, SearchIntent.ARCHIVAL),
                classify("Find archived versions of https://example.com", "", List.of()));
    }

    @Test
    void explicitRecencyAndCategoriesInfluenceRouting() {
        assertEquals(List.of(SearchIntent.GENERAL, SearchIntent.CURRENT_NEWS),
                classify("Spring Boot", "month", List.of()));
        assertEquals(List.of(SearchIntent.GENERAL, SearchIntent.SCHOLARLY),
                classify("quantum computing", "", List.of("science")));
    }

    private static List<SearchIntent> classify(String query, String timeRange, List<String> categories) {
        return QueryIntentClassifier.classify(
                new SearchRequest(query, 6, "auto", timeRange, 1, 1, categories));
    }
}
