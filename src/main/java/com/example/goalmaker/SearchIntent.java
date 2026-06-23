package com.example.goalmaker;

enum SearchIntent {
    GENERAL("general"),
    CURRENT_NEWS("current-news"),
    FACTUAL_ENTITY("factual-entity"),
    SCHOLARLY("scholarly"),
    ARCHIVAL("archival");

    private final String value;

    SearchIntent(String value) {
        this.value = value;
    }

    String value() {
        return value;
    }
}
