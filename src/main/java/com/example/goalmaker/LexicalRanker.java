package com.example.goalmaker;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Small, dependency-free BM25 ranker used to order search results and select evidence
 * sentences by lexical relevance to a query. Building the ranker over the candidate set
 * itself supplies the document-frequency statistics, which is the standard approach for
 * re-ranking a single result list. It is deterministic, runs locally, and needs no model
 * call or API token, so it improves retrieval quality without changing the resource budget.
 */
final class LexicalRanker {
    private static final double K1 = 1.5;
    private static final double B = 0.75;
    private static final Set<String> STOP_WORDS = Set.of(
            "about", "after", "all", "an", "and", "any", "are", "as", "at", "be", "been", "before",
            "but", "by", "can", "did", "do", "does", "for", "from", "had", "has", "have", "how", "in",
            "into", "is", "it", "its", "latest", "most", "of", "on", "or", "that", "the", "their",
            "then", "there", "these", "they", "this", "to", "was", "were", "what", "when", "where",
            "which", "who", "why", "will", "with", "would", "you", "your");

    private final List<List<String>> documents;
    private final Map<String, Integer> documentFrequency;
    private final double averageLength;

    private LexicalRanker(List<List<String>> documents, Map<String, Integer> documentFrequency,
                          double averageLength) {
        this.documents = documents;
        this.documentFrequency = documentFrequency;
        this.averageLength = averageLength;
    }

    static LexicalRanker forDocuments(List<String> texts) {
        List<List<String>> documents = new ArrayList<>(texts.size());
        Map<String, Integer> documentFrequency = new HashMap<>();
        long totalLength = 0;
        for (String text : texts) {
            List<String> tokens = tokenize(text);
            documents.add(tokens);
            totalLength += tokens.size();
            for (String term : new HashSet<>(tokens)) {
                documentFrequency.merge(term, 1, Integer::sum);
            }
        }
        double averageLength = documents.isEmpty() ? 0 : (double) totalLength / documents.size();
        return new LexicalRanker(documents, documentFrequency, averageLength);
    }

    /** BM25 relevance of the query against the document at the supplied index. */
    double score(String query, int documentIndex) {
        if (documentIndex < 0 || documentIndex >= documents.size() || averageLength <= 0) return 0;
        List<String> document = documents.get(documentIndex);
        if (document.isEmpty()) return 0;
        Map<String, Integer> frequencies = new HashMap<>();
        for (String term : document) frequencies.merge(term, 1, Integer::sum);
        int total = documents.size();
        double score = 0;
        for (String term : new HashSet<>(tokenize(query))) {
            int frequency = frequencies.getOrDefault(term, 0);
            if (frequency == 0) continue;
            int df = documentFrequency.getOrDefault(term, 0);
            double idf = Math.log(1 + (total - df + 0.5) / (df + 0.5));
            double denominator = frequency + K1 * (1 - B + B * document.size() / averageLength);
            score += idf * (frequency * (K1 + 1)) / denominator;
        }
        return score;
    }

    /** Number of distinct query terms present in the supplied text; a bounded relevance signal. */
    static int termCoverage(String query, String text) {
        Set<String> terms = new HashSet<>(tokenize(query));
        if (terms.isEmpty()) return 0;
        Set<String> present = new HashSet<>(tokenize(text));
        int covered = 0;
        for (String term : terms) if (present.contains(term)) covered++;
        return covered;
    }

    static List<String> tokenize(String text) {
        if (text == null || text.isBlank()) return List.of();
        List<String> tokens = new ArrayList<>();
        for (String token : text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (token.length() >= 2 && !STOP_WORDS.contains(token)) tokens.add(token);
        }
        return tokens;
    }
}
