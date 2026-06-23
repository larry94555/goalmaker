package com.example.goalmaker;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

final class QueryIntentClassifier {
    private static final Pattern NEWS = Pattern.compile(
            "\\b(latest|current|today|yesterday|recent|breaking|news|headline|this week|this month|"
                    + "announced|announcement|developments?|updates?)\\b");
    private static final Pattern SCHOLARLY = Pattern.compile(
            "\\b(arxiv|preprints?|research papers?|papers?|stud(?:y|ies)|journals?|scholarly|academic|"
                    + "literature review|systematic review|doi)\\b");
    private static final Pattern ARCHIVAL = Pattern.compile(
            "\\b(archives?|archived|historical snapshots?|cached cop(?:y|ies)|common crawl|"
                    + "old versions?|previous versions?|web history)\\b");
    private static final Pattern ENTITY = Pattern.compile(
            "^(who is|who was|where is|where was|when was|what is|what was|how many|how much)\\b|"
                    + "\\b(capital of|population of|define|definition|biography|born|founded|headquarters|"
                    + "official name|located)\\b");

    private QueryIntentClassifier() {}

    static List<SearchIntent> classify(SearchRequest request) {
        String query = request.query().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
        List<SearchIntent> intents = new ArrayList<>();
        intents.add(SearchIntent.GENERAL);
        if (!request.timeRange().isBlank() || containsCategory(request, "news") || NEWS.matcher(query).find()) {
            intents.add(SearchIntent.CURRENT_NEWS);
        }
        if (containsCategory(request, "science") || SCHOLARLY.matcher(query).find()) {
            intents.add(SearchIntent.SCHOLARLY);
        }
        if (ARCHIVAL.matcher(query).find()) intents.add(SearchIntent.ARCHIVAL);
        if (ENTITY.matcher(query).find()
                && !intents.contains(SearchIntent.SCHOLARLY)
                && !intents.contains(SearchIntent.ARCHIVAL)) {
            intents.add(SearchIntent.FACTUAL_ENTITY);
        }
        return List.copyOf(intents);
    }

    private static boolean containsCategory(SearchRequest request, String category) {
        return request.categories().stream().anyMatch(value -> category.equalsIgnoreCase(value));
    }
}
