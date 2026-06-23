package com.example.goalmaker;

import java.util.Set;

record FetchSettings(
        int maxAttempts,
        long retryDelayMillis,
        int maxResponseBytes,
        int maxRedirects,
        int defaultMaxChars,
        boolean allowPrivateAddresses,
        Set<Integer> allowedPorts,
        int totalBudgetSeconds,
        int maxHttpRequests,
        boolean robotsEnabled,
        int robotsTimeoutSeconds,
        int robotsMaxAttempts,
        long robotsRetryDelayMillis,
        int robotsMaxResponseBytes,
        int robotsMaxRedirects,
        int robotsCacheTtlSeconds,
        int robotsCacheMaxEntries,
        int pdfMaxPages,
        int pdfTimeoutSeconds) {

    FetchSettings {
        allowedPorts = allowedPorts == null ? Set.of() : Set.copyOf(allowedPorts);
    }
}
