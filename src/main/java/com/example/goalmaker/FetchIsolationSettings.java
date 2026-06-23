package com.example.goalmaker;

record FetchIsolationSettings(int poolSize, int memoryMb, int activeProcessors,
                              int timeoutSeconds, int maxOutputBytes, String mainClass) {
    FetchIsolationSettings {
        poolSize = Math.max(1, poolSize);
        memoryMb = Math.max(64, memoryMb);
        activeProcessors = Math.max(1, activeProcessors);
        timeoutSeconds = Math.max(1, timeoutSeconds);
        maxOutputBytes = Math.max(1_024, maxOutputBytes);
        mainClass = mainClass == null || mainClass.isBlank()
                ? FetchWorkerMain.class.getName() : mainClass.trim();
    }

    String fingerprint() {
        return memoryMb + ":" + activeProcessors + ":" + mainClass;
    }
}
