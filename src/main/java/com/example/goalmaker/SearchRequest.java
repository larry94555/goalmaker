package com.example.goalmaker;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

record SearchRequest(String query, int maxResults, String language, String timeRange,
                     int page, int safeSearch, List<String> categories) {
    private static final List<String> TIME_RANGES = List.of("", "day", "month", "year");

    static SearchRequest from(Map<String, Object> arguments) {
        String query = string(arguments.get("query")).trim();
        if (query.isBlank()) throw new IllegalArgumentException("query is required");
        int maxResults = integer(arguments.get("max_results"), 6, 1, 20);
        String language = string(arguments.get("language")).trim();
        if (language.isBlank()) language = "auto";
        String timeRange = string(arguments.get("time_range")).trim().toLowerCase(Locale.ROOT);
        if (!TIME_RANGES.contains(timeRange)) {
            throw new IllegalArgumentException("time_range must be day, month, or year");
        }
        int page = integer(arguments.get("page"), 1, 1, 10);
        int safeSearch = integer(arguments.get("safe_search"), 1, 0, 2);
        List<String> categories = strings(arguments.get("categories"));
        return new SearchRequest(query, maxResults, language, timeRange, page, safeSearch, categories);
    }

    String cacheKey() {
        return String.join("|", query.toLowerCase(Locale.ROOT), String.valueOf(maxResults),
                language.toLowerCase(Locale.ROOT), timeRange, String.valueOf(page),
                String.valueOf(safeSearch), String.join(",", categories));
    }

    private static String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static int integer(Object value, int fallback, int minimum, int maximum) {
        int parsed;
        try {
            parsed = value == null ? fallback : Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("numeric search option is invalid");
        }
        if (parsed < minimum || parsed > maximum) {
            throw new IllegalArgumentException("numeric search option must be between "
                    + minimum + " and " + maximum);
        }
        return parsed;
    }

    private static List<String> strings(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        List<String> values = new ArrayList<>();
        for (Object item : list) {
            String normalized = string(item).trim();
            if (!normalized.isBlank() && values.size() < 8) values.add(normalized);
        }
        return List.copyOf(values);
    }
}
