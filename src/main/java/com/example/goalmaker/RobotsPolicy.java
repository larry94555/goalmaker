package com.example.goalmaker;

import crawlercommons.robots.BaseRobotRules;
import crawlercommons.robots.SimpleRobotRulesParser;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

final class RobotsPolicy {
    private static final String USER_AGENT = "goalmaker";

    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    Decision check(URI target, Settings settings, UrlValidator validator, FetchHttpClient http,
                   FetchBudget budget) {
        if (!settings.enabled()) return new Decision(true, "disabled", "", false, "");
        URI robotsUrl = robotsUri(target);
        String key = origin(robotsUrl);
        CacheEntry cached = cache.get(key);
        if (cached != null && cached.expiresAt().isAfter(Instant.now())) {
            return decision(cached.policy(), target, true);
        }

        LoadedPolicy policy = load(robotsUrl, settings, validator, http, budget);
        if (!settings.cacheTtl().isZero() && !settings.cacheTtl().isNegative()
                && settings.cacheMaxEntries() > 0) {
            Instant now = Instant.now();
            cache.entrySet().removeIf(entry -> entry.getValue().expiresAt().isBefore(now));
            if (cache.size() >= settings.cacheMaxEntries()) {
                cache.keySet().stream().findFirst().ifPresent(cache::remove);
            }
            cache.put(key, new CacheEntry(policy, now.plus(settings.cacheTtl())));
        }
        return decision(policy, target, false);
    }

    private LoadedPolicy load(URI robotsUrl, Settings settings, UrlValidator validator,
                              FetchHttpClient http, FetchBudget budget) {
        SimpleRobotRulesParser parser = new SimpleRobotRulesParser();
        URI current = robotsUrl;
        try {
            for (int redirect = 0; redirect <= settings.maxRedirects(); redirect++) {
                validator.validate(current);
                FetchHttpClient.Response response = http.getBytes(current, "text/plain, */*;q=0.1",
                        settings.timeout(), settings.maxAttempts(), settings.retryDelayMillis(),
                        settings.maxResponseBytes(), budget);
                if (redirect(response.status())) {
                    String location = response.firstHeader("Location")
                            .orElseThrow(() -> new IllegalStateException(
                                    "robots.txt redirect had no Location header"));
                    current = current.resolve(location);
                    if (redirect == settings.maxRedirects()) {
                        return unavailable(parser, robotsUrl, "too many robots.txt redirects");
                    }
                    continue;
                }
                if (response.status() / 100 == 2 && !response.truncated()) {
                    String contentType = response.firstHeader("Content-Type").orElse("text/plain");
                    BaseRobotRules rules = parser.parseContent(current.toString(), response.body(),
                            contentType, List.of(USER_AGENT));
                    return new LoadedPolicy(rules, "rules", robotsUrl.toString(), "");
                }
                if (response.truncated()) {
                    return unavailable(parser, robotsUrl, "robots.txt exceeded size limit");
                }
                BaseRobotRules rules = parser.failedFetch(response.status());
                String status = response.status() == 404 || response.status() == 410
                        ? "not-found" : "http-" + response.status();
                return new LoadedPolicy(rules, status, robotsUrl.toString(),
                        "robots.txt was temporarily unavailable");
            }
        } catch (Exception error) {
            return unavailable(parser, robotsUrl, usefulMessage(error));
        }
        return unavailable(parser, robotsUrl, "robots.txt check failed");
    }

    private static LoadedPolicy unavailable(SimpleRobotRulesParser parser, URI robotsUrl, String reason) {
        return new LoadedPolicy(parser.failedFetch(503), "unavailable", robotsUrl.toString(), reason);
    }

    private static Decision decision(LoadedPolicy policy, URI target, boolean cached) {
        boolean allowed = policy.rules().isAllowed(target.toString());
        String status = policy.status().equals("rules")
                ? allowed ? "allowed" : "disallowed" : policy.status();
        String reason = allowed ? "" : policy.status().equals("rules")
                ? "robots.txt disallows this URL for " + USER_AGENT : policy.denialReason();
        return new Decision(allowed, status, policy.robotsUrl(), cached, reason);
    }

    private static URI robotsUri(URI target) {
        try {
            return new URI(target.getScheme(), null, target.getHost(), target.getPort(),
                    "/robots.txt", null, null);
        } catch (Exception error) {
            throw new IllegalArgumentException("could not construct robots.txt URL", error);
        }
    }

    private static String origin(URI uri) {
        return uri.getScheme() + "://" + uri.getHost() + (uri.getPort() < 0 ? "" : ":" + uri.getPort());
    }

    private static boolean redirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    private static String usefulMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }

    @FunctionalInterface
    interface UrlValidator {
        void validate(URI uri) throws Exception;
    }

    record Settings(boolean enabled, Duration timeout, int maxAttempts, long retryDelayMillis,
                    int maxResponseBytes, int maxRedirects, Duration cacheTtl,
                    int cacheMaxEntries) {}

    record Decision(boolean allowed, String status, String robotsUrl, boolean cached, String reason) {}

    private record LoadedPolicy(BaseRobotRules rules, String status, String robotsUrl,
                                String denialReason) {}

    private record CacheEntry(LoadedPolicy policy, Instant expiresAt) {}
}
