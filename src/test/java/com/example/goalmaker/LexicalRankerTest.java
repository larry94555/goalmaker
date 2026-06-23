package com.example.goalmaker;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LexicalRankerTest {
    @Test
    void tokenizeLowercasesAndDropsStopWordsAndShortTokens() {
        assertEquals(List.of("java", "garbage", "collection"),
                LexicalRanker.tokenize("The Java garbage collection of a"));
        assertEquals(List.of(), LexicalRanker.tokenize("  "));
    }

    @Test
    void scoresRelevantDocumentAboveIrrelevantOne() {
        LexicalRanker ranker = LexicalRanker.forDocuments(List.of(
                "Pasta carbonara recipe with eggs",
                "Java garbage collection tuning guide for the JVM collector"));

        double irrelevant = ranker.score("java garbage collection tuning", 0);
        double relevant = ranker.score("java garbage collection tuning", 1);

        assertEquals(0.0, irrelevant);
        assertTrue(relevant > 0, "relevant document should score above zero");
    }

    @Test
    void rewardsRarerTermsMoreThanCommonOnes() {
        LexicalRanker ranker = LexicalRanker.forDocuments(List.of(
                "common common common rare",
                "common common common other"));

        // "common" appears in every document (low IDF) while "rare" appears in one (high IDF), so a
        // match on the rare term outweighs a higher-frequency match on the common term.
        double commonTerm = ranker.score("common", 0);
        double rareTerm = ranker.score("rare", 0);

        assertTrue(rareTerm > commonTerm, "rarer matching term should carry more weight");
    }

    @Test
    void termCoverageCountsDistinctQueryTerms() {
        assertEquals(2, LexicalRanker.termCoverage(
                "spring boot release", "Spring Boot 4.0 ships a new baseline"));
        assertEquals(0, LexicalRanker.termCoverage("kubernetes", "Spring Boot release notes"));
    }
}
