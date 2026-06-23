package com.example.goalmaker;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
public class WebSearchHealthController {
    private final SearxngHealthManager health;
    private final Map<String, Boolean> fallbacks;

    public WebSearchHealthController(SearxngHealthManager health) {
        this(health, Map.of("duckduckgo", true));
    }

    @Autowired
    public WebSearchHealthController(SearxngHealthManager health, Environment environment) {
        this(health, configuredFallbacks(environment));
    }

    WebSearchHealthController(SearxngHealthManager health, Map<String, Boolean> fallbacks) {
        this.health = health;
        this.fallbacks = Map.copyOf(fallbacks);
    }

    @GetMapping("/health/web-search")
    public ResponseEntity<Map<String, Object>> health() {
        SearxngHealthManager.Snapshot snapshot = health.snapshot();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("provider", "searxng");
        body.put("status", snapshot.status());
        body.put("configured", snapshot.configured());
        body.put("managed", snapshot.managed());
        body.put("endpoint", snapshot.endpoint());
        body.put("search_allowed", snapshot.searchAllowed());
        if (snapshot.lastCheckedAt() != null) body.put("last_checked_at", snapshot.lastCheckedAt());
        if (snapshot.lastSuccessAt() != null) body.put("last_success_at", snapshot.lastSuccessAt());
        if (snapshot.latencyMillis() != null) body.put("latency_millis", snapshot.latencyMillis());
        body.put("consecutive_failures", snapshot.consecutiveFailures());
        if (!snapshot.recentFailure().isBlank()) body.put("recent_failure", snapshot.recentFailure());
        if (snapshot.circuitOpenUntil() != null) {
            body.put("circuit_open_until", snapshot.circuitOpenUntil());
        }
        body.put("metrics", Map.of(
                "total_probes", snapshot.totalProbes(),
                "successful_probes", snapshot.successfulProbes(),
                "failed_probes", snapshot.failedProbes(),
                "search_successes", snapshot.searchSuccesses(),
                "search_failures", snapshot.searchFailures(),
                "skipped_searches", snapshot.skippedSearches()));
        body.put("fallbacks", fallbacks);
        body.put("fallbacks_remain_available", fallbacks.values().stream().anyMatch(Boolean::booleanValue));

        HttpStatus status = switch (snapshot.status()) {
            case "starting" -> HttpStatus.ACCEPTED;
            case "unavailable" -> HttpStatus.SERVICE_UNAVAILABLE;
            default -> HttpStatus.OK;
        };
        return ResponseEntity.status(status).body(body);
    }

    private static Map<String, Boolean> configuredFallbacks(Environment environment) {
        Map<String, Boolean> configured = new LinkedHashMap<>();
        configured.put("duckduckgo", configured(environment,
                "web.search.duckduckgo-url", "https://html.duckduckgo.com/html/"));
        configured.put("mediawiki", configured(environment,
                "web.specialized.mediawiki.url", "https://en.wikipedia.org/w/api.php"));
        configured.put("wikidata", configured(environment,
                "web.specialized.wikidata.url", "https://www.wikidata.org/w/api.php"));
        configured.put("arxiv", configured(environment,
                "web.specialized.arxiv.url", "https://export.arxiv.org/api/query"));
        configured.put("gdelt", configured(environment,
                "web.specialized.gdelt.url", "https://api.gdeltproject.org/api/v2/doc/doc"));
        configured.put("commoncrawl", configured(environment,
                "web.specialized.commoncrawl.url", "https://index.commoncrawl.org/collinfo.json"));
        return configured;
    }

    private static boolean configured(Environment environment, String key, String fallback) {
        return !environment.getProperty(key, fallback).trim().isBlank();
    }
}
