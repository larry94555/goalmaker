package com.example.goalmaker;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

final class FetchBudget {
    private final long deadlineNanos;
    private final int maxHttpRequests;
    private int httpRequests;

    FetchBudget(Duration total, int maxHttpRequests) {
        long durationNanos = Math.max(1, total.toNanos());
        long now = System.nanoTime();
        this.deadlineNanos = Long.MAX_VALUE - now < durationNanos
                ? Long.MAX_VALUE : now + durationNanos;
        this.maxHttpRequests = Math.max(1, maxHttpRequests);
    }

    synchronized void consumeHttpRequest() {
        check("web fetch total budget exceeded");
        if (++httpRequests > maxHttpRequests) {
            throw new IllegalStateException("web fetch HTTP request budget exceeded");
        }
    }

    void check(String message) {
        if (remainingMillis() <= 0) throw new IllegalStateException(message);
    }

    long boundedMillis(Duration requested) {
        check("web fetch total time budget exceeded");
        return Math.max(1, Math.min(Math.max(1, requested.toMillis()), remainingMillis()));
    }

    long remainingMillis() {
        long remaining = deadlineNanos - System.nanoTime();
        return remaining <= 0 ? 0 : Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining));
    }

    void sleep(long millis) throws InterruptedException {
        if (millis <= 0) return;
        long bounded = Math.min(millis, remainingMillis());
        if (bounded <= 0) throw new IllegalStateException("web fetch total time budget exceeded");
        Thread.sleep(bounded);
        check("web fetch total time budget exceeded");
    }
}
