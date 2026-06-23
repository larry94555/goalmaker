package com.example.goalmaker;

import java.util.Locale;

record FetchIsolationSettings(
        String mode,
        int poolSize,
        int memoryMb,
        int activeProcessors,
        int timeoutSeconds,
        int maxOutputBytes,
        String mainClass,
        String dockerCommand,
        String dockerImage,
        boolean dockerAutoBuild,
        String dockerfile,
        String dockerContext,
        String dockerBuildLog,
        int dockerBuildTimeoutSeconds,
        int dockerMemoryMb,
        double dockerCpus,
        int dockerPidsLimit,
        int dockerTmpfsMb) {

    FetchIsolationSettings {
        mode = mode == null || mode.isBlank() ? "process" : mode.trim().toLowerCase(Locale.ROOT);
        if (!mode.equals("process") && !mode.equals("docker")) {
            throw new IllegalArgumentException("web.fetch.worker.mode must be process or docker");
        }
        poolSize = Math.max(1, poolSize);
        memoryMb = Math.max(64, memoryMb);
        activeProcessors = Math.max(1, activeProcessors);
        timeoutSeconds = Math.max(1, timeoutSeconds);
        maxOutputBytes = Math.max(1_024, maxOutputBytes);
        mainClass = defaultValue(mainClass, FetchWorkerMain.class.getName());
        dockerCommand = defaultValue(dockerCommand, "docker");
        dockerImage = defaultValue(dockerImage, "goalmaker-fetch-worker:local");
        dockerfile = defaultValue(dockerfile, "docker/fetch-worker/Dockerfile");
        dockerContext = defaultValue(dockerContext, ".");
        dockerBuildLog = defaultValue(dockerBuildLog, "fetch-worker-docker-build.log");
        dockerBuildTimeoutSeconds = Math.max(30, dockerBuildTimeoutSeconds);
        dockerMemoryMb = Math.max(memoryMb + 64, dockerMemoryMb);
        dockerCpus = Math.max(0.1, dockerCpus);
        dockerPidsLimit = Math.max(16, dockerPidsLimit);
        dockerTmpfsMb = Math.max(8, dockerTmpfsMb);
    }

    FetchIsolationSettings(int poolSize, int memoryMb, int activeProcessors,
                           int timeoutSeconds, int maxOutputBytes, String mainClass) {
        this("process", poolSize, memoryMb, activeProcessors, timeoutSeconds, maxOutputBytes, mainClass,
                "docker", "goalmaker-fetch-worker:local", false,
                "docker/fetch-worker/Dockerfile", ".", "fetch-worker-docker-build.log",
                600, 384, 1.0, 64, 32);
    }

    boolean docker() {
        return mode.equals("docker");
    }

    String fingerprint() {
        if (!docker()) return mode + ":" + memoryMb + ":" + activeProcessors + ":" + mainClass;
        return mode + ":" + dockerImage + ":" + memoryMb + ":" + activeProcessors + ":"
                + dockerMemoryMb + ":" + dockerCpus + ":" + dockerPidsLimit + ":" + dockerTmpfsMb;
    }

    private static String defaultValue(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
