package com.example.goalmaker;

import java.util.List;

interface SearchProvider {
    String name();

    List<SearchResult> search(SearchRequest request) throws Exception;
}
