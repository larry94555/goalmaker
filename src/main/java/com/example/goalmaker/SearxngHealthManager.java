package com.example.goalmaker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class SearxngHealthManager {
    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(SearxngHealthManager.class);

    enum State {
        DISABLED("disabled"),
        STARTING("starting"),
        HEALTHY("healthy"),
        DEGRADED("degraded"),
        UNAVAILABLE("unavailable");

        private final String value;

        State(String value) {
            this.value = value;
        }

        String value() {
            return value;
        }
    }

    private final Config config;
    private final Probe probe;
    private final ComposeStarter composeStarter;
    private final Clock clock;
    private final AtomicBoolean probeInProgress = new AtomicBoolean();
    private volatile ScheduledExecutorService scheduler;

    private State state;
    private Instant lastCheckedAt;
    private Instant lastSuccessAt;
    private Long latencyMillis;
    private int consecutiveFailures;
    private String recentFailure = "";
    private Instant circuitOpenUntil;
    private boolean halfOpenSearchInProgress;
    private long totalProbes;
    private long successfulProbes;
    private long failedProbes;
    private long searchSuccesses;
    private long searchFailures;
    private long skippedSearches;

    @Autowired
    public SearxngHealthManager(ObjectMapper mapper, Environment environment) {
        this(Config.from(environment),
                new HttpProbe(mapper, HttpClient.newBuilder()
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .connectTimeout(Duration.ofSeconds(5))
                        .build()),
                new DockerComposeStarter(), Clock.systemUTC());
    }

    SearxngHealthManager(Config config, Probe probe, ComposeStarter composeStarter, Clock clock) {
        this.config = config;
        this.probe = probe;
        this.composeStarter = composeStarter;
        this.clock = clock;
        this.state = config.configured() ? State.STARTING : State.DISABLED;
    }

    @PostConstruct
    public void start() {
        if (!config.configured()) {
            log.info("[web-search] SearXNG disabled because no endpoint is configured");
            return;
        }
        scheduler = Executors.newScheduledThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "searxng-health");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.execute(this::initialize);
        scheduler.scheduleWithFixedDelay(() -> probeNow(false),
                config.healthInterval().toMillis(), config.healthInterval().toMillis(),
                TimeUnit.MILLISECONDS);
    }

    void initialize() {
        ProbeResult initial = probeNow(config.manage());
        if (initial != null && initial.success()) return;
        if (!config.manage()) return;

        StartResult startResult = composeStarter.start(config);
        if (!startResult.success()) {
            markUnavailable("managed SearXNG startup failed: " + startResult.message());
            return;
        }
        synchronized (this) {
            state = State.STARTING;
            recentFailure = "";
            circuitOpenUntil = null;
        }
        log.info("[web-search] started SearXNG with Docker Compose; waiting for readiness");
        long deadline = System.nanoTime() + config.startupTimeout().toNanos();
        while (System.nanoTime() < deadline) {
            try {
                Thread.sleep(config.startupPollInterval().toMillis());
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                return;
            }
            ProbeResult result = probeNow(true);
            if (result != null && result.success()) return;
        }
        markUnavailable("SearXNG did not become ready within "
                + config.startupTimeout().toSeconds() + " seconds");
    }

    ProbeResult probeNow() {
        return probeNow(false);
    }

    private ProbeResult probeNow(boolean keepStartingOnFailure) {
        if (!config.configured() || !probeInProgress.compareAndSet(false, true)) return null;
        try {
            ProbeResult result;
            try {
                result = probe.probe(config);
            } catch (Exception error) {
                result = ProbeResult.failure(0, usefulMessage(error));
            }
            applyProbe(result, keepStartingOnFailure);
            return result;
        } finally {
            probeInProgress.set(false);
        }
    }

    synchronized SearchPermission acquireSearchPermission() {
        Instant now = clock.instant();
        if (state == State.DISABLED) return denied("SearXNG is disabled");
        if (state == State.STARTING) return denied("SearXNG is still starting");
        if (state == State.UNAVAILABLE) {
            if (circuitOpenUntil != null && now.isBefore(circuitOpenUntil)) {
                return denied("SearXNG circuit is open until " + circuitOpenUntil);
            }
            if (halfOpenSearchInProgress) {
                return denied("SearXNG recovery request is already in progress");
            }
            halfOpenSearchInProgress = true;
            return new SearchPermission(true, "SearXNG circuit is half-open for a recovery request");
        }
        return new SearchPermission(true, "SearXNG is " + state.value());
    }

    synchronized void recordSearchSuccess(long requestLatencyMillis) {
        State previous = state;
        Instant now = clock.instant();
        searchSuccesses++;
        lastCheckedAt = now;
        lastSuccessAt = now;
        latencyMillis = Math.max(0, requestLatencyMillis);
        consecutiveFailures = 0;
        recentFailure = "";
        circuitOpenUntil = null;
        halfOpenSearchInProgress = false;
        state = latencyMillis >= config.degradedLatency().toMillis()
                ? State.DEGRADED : State.HEALTHY;
        logTransition(previous);
    }

    synchronized void recordSearchFailure(String message) {
        State previous = state;
        searchFailures++;
        lastCheckedAt = clock.instant();
        latencyMillis = null;
        consecutiveFailures++;
        recentFailure = message == null || message.isBlank() ? "SearXNG search failed" : message;
        halfOpenSearchInProgress = false;
        transitionAfterFailure();
        logTransition(previous);
    }

    synchronized Snapshot snapshot() {
        return new Snapshot(state.value(), config.configured(), config.manage(), safeEndpoint(config.endpoint()),
                lastCheckedAt, lastSuccessAt, latencyMillis, consecutiveFailures, recentFailure,
                circuitOpenUntil, totalProbes, successfulProbes, failedProbes,
                searchSuccesses, searchFailures, skippedSearches, searchAllowed(clock.instant()));
    }

    Map<String, Object> summary() {
        Snapshot snapshot = snapshot();
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("status", snapshot.status());
        summary.put("search_allowed", snapshot.searchAllowed());
        if (snapshot.lastSuccessAt() != null) {
            summary.put("last_success_at", snapshot.lastSuccessAt().toString());
        }
        if (snapshot.latencyMillis() != null) summary.put("latency_millis", snapshot.latencyMillis());
        if (!snapshot.recentFailure().isBlank()) summary.put("recent_failure", snapshot.recentFailure());
        if (snapshot.circuitOpenUntil() != null) {
            summary.put("circuit_open_until", snapshot.circuitOpenUntil().toString());
        }
        return summary;
    }

    @PreDestroy
    public void stop() {
        ScheduledExecutorService current = scheduler;
        if (current != null) current.shutdownNow();
    }

    private synchronized void applyProbe(ProbeResult result, boolean keepStartingOnFailure) {
        State previous = state;
        Instant now = clock.instant();
        totalProbes++;
        lastCheckedAt = now;
        latencyMillis = Math.max(0, result.latencyMillis());
        if (result.success()) {
            successfulProbes++;
            lastSuccessAt = now;
            consecutiveFailures = 0;
            recentFailure = "";
            circuitOpenUntil = null;
            halfOpenSearchInProgress = false;
            state = latencyMillis >= config.degradedLatency().toMillis()
                    ? State.DEGRADED : State.HEALTHY;
            logTransition(previous);
            return;
        }

        failedProbes++;
        consecutiveFailures++;
        recentFailure = result.error();
        halfOpenSearchInProgress = false;
        if (keepStartingOnFailure && state == State.STARTING) return;
        transitionAfterFailure();
        logTransition(previous);
    }

    private void transitionAfterFailure() {
        if ((state == State.HEALTHY || state == State.DEGRADED)
                && consecutiveFailures < config.failureThreshold()) {
            state = State.DEGRADED;
            return;
        }
        state = State.UNAVAILABLE;
        circuitOpenUntil = clock.instant().plus(config.circuitBreakerDuration());
    }

    private synchronized void markUnavailable(String failure) {
        State previous = state;
        state = State.UNAVAILABLE;
        recentFailure = failure;
        consecutiveFailures = Math.max(consecutiveFailures, config.failureThreshold());
        circuitOpenUntil = clock.instant().plus(config.circuitBreakerDuration());
        halfOpenSearchInProgress = false;
        logTransition(previous);
    }

    private SearchPermission denied(String reason) {
        skippedSearches++;
        return new SearchPermission(false, reason);
    }

    private boolean searchAllowed(Instant now) {
        if (state == State.HEALTHY || state == State.DEGRADED) return true;
        return state == State.UNAVAILABLE
                && !halfOpenSearchInProgress
                && (circuitOpenUntil == null || !now.isBefore(circuitOpenUntil));
    }

    private void logTransition(State previous) {
        if (previous == state) return;
        if (state == State.UNAVAILABLE) {
            log.warn("[web-search] SearXNG state {} -> {}: {}", previous.value(), state.value(), recentFailure);
        } else {
            log.info("[web-search] SearXNG state {} -> {}", previous.value(), state.value());
        }
    }

    private static String usefulMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }

    private static String safeEndpoint(String endpoint) {
        try {
            URI uri = URI.create(endpoint);
            return new URI(uri.getScheme(), null, uri.getHost(), uri.getPort(),
                    uri.getPath(), null, null).toString();
        } catch (Exception error) {
            return "(invalid endpoint)";
        }
    }

    record SearchPermission(boolean allowed, String reason) {}

    record Snapshot(String status, boolean configured, boolean managed, String endpoint,
                    Instant lastCheckedAt, Instant lastSuccessAt, Long latencyMillis,
                    int consecutiveFailures, String recentFailure, Instant circuitOpenUntil,
                    long totalProbes, long successfulProbes, long failedProbes,
                    long searchSuccesses, long searchFailures, long skippedSearches,
                    boolean searchAllowed) {}

    record ProbeResult(boolean success, long latencyMillis, String error) {
        static ProbeResult success(long latencyMillis) {
            return new ProbeResult(true, latencyMillis, "");
        }

        static ProbeResult failure(long latencyMillis, String error) {
            return new ProbeResult(false, latencyMillis,
                    error == null || error.isBlank() ? "SearXNG health probe failed" : error);
        }
    }

    record StartResult(boolean success, String message) {
        static StartResult started() {
            return new StartResult(true, "started");
        }

        static StartResult failed(String message) {
            return new StartResult(false, message);
        }
    }

    @FunctionalInterface
    interface Probe {
        ProbeResult probe(Config config) throws Exception;
    }

    @FunctionalInterface
    interface ComposeStarter {
        StartResult start(Config config);
    }

    record Config(String endpoint, boolean manage, Duration healthInterval,
                  Duration probeTimeout, int failureThreshold,
                  Duration circuitBreakerDuration, Duration degradedLatency,
                  Duration startupTimeout, Duration startupPollInterval,
                  String dockerCommand, String composeFile, String composeLog,
                  Duration composeCommandTimeout) {
        Config {
            endpoint = endpoint == null ? "" : endpoint.trim();
            healthInterval = positive(healthInterval, Duration.ofSeconds(30));
            probeTimeout = positive(probeTimeout, Duration.ofSeconds(2));
            failureThreshold = Math.max(1, failureThreshold);
            circuitBreakerDuration = positive(circuitBreakerDuration, Duration.ofSeconds(30));
            degradedLatency = positive(degradedLatency, Duration.ofMillis(1500));
            startupTimeout = positive(startupTimeout, Duration.ofSeconds(60));
            startupPollInterval = positive(startupPollInterval, Duration.ofSeconds(1));
            dockerCommand = dockerCommand == null || dockerCommand.isBlank() ? "docker" : dockerCommand.trim();
            composeFile = composeFile == null || composeFile.isBlank()
                    ? "docker-compose.searxng.yml" : composeFile.trim();
            composeLog = composeLog == null || composeLog.isBlank()
                    ? "searxng-compose.log" : composeLog.trim();
            composeCommandTimeout = positive(composeCommandTimeout, Duration.ofSeconds(30));
        }

        boolean configured() {
            return !endpoint.isBlank();
        }

        static Config from(Environment environment) {
            return new Config(
                    environment.getProperty("web.search.searxng-url", "http://127.0.0.1:8888/search"),
                    environment.getProperty("web.search.searxng-manage", Boolean.class, false),
                    seconds(environment, "web.search.searxng-health-interval-seconds", 30),
                    millis(environment, "web.search.searxng-health-timeout-millis", 2000),
                    environment.getProperty("web.search.searxng-failure-threshold", Integer.class, 2),
                    seconds(environment, "web.search.searxng-circuit-breaker-seconds", 30),
                    millis(environment, "web.search.searxng-degraded-latency-millis", 1500),
                    seconds(environment, "web.search.searxng-startup-timeout-seconds", 60),
                    millis(environment, "web.search.searxng-startup-poll-millis", 1000),
                    environment.getProperty("web.search.searxng-docker-command", "docker"),
                    environment.getProperty("web.search.searxng-compose-file", "docker-compose.searxng.yml"),
                    environment.getProperty("web.search.searxng-compose-log", "searxng-compose.log"),
                    seconds(environment, "web.search.searxng-compose-command-timeout-seconds", 30));
        }

        private static Duration positive(Duration value, Duration fallback) {
            return value == null || value.isZero() || value.isNegative() ? fallback : value;
        }

        private static Duration seconds(Environment environment, String key, long fallback) {
            return Duration.ofSeconds(environment.getProperty(key, Long.class, fallback));
        }

        private static Duration millis(Environment environment, String key, long fallback) {
            return Duration.ofMillis(environment.getProperty(key, Long.class, fallback));
        }
    }

    static final class HttpProbe implements Probe {
        private final ObjectMapper mapper;
        private final WebHttpClient http;

        HttpProbe(ObjectMapper mapper, HttpClient client) {
            this.mapper = mapper;
            this.http = new WebHttpClient(client);
        }

        @Override
        public ProbeResult probe(Config config) {
            long started = System.nanoTime();
            try {
                WebHttpClient.Response response = http.get(probeUri(config.endpoint()), "application/json",
                        config.probeTimeout(), 1, 0, 262_144);
                long latency = elapsedMillis(started);
                if (response.status() / 100 != 2) {
                    return ProbeResult.failure(latency, "HTTP " + response.status() + " from SearXNG health probe");
                }
                if (response.truncated()) {
                    return ProbeResult.failure(latency, "SearXNG health response exceeded size limit");
                }
                JsonNode root = mapper.readTree(response.body());
                if (!root.isObject() || !root.path("engines").isArray()) {
                    return ProbeResult.failure(latency, "SearXNG health response was not valid config JSON");
                }
                return ProbeResult.success(latency);
            } catch (Exception error) {
                return ProbeResult.failure(elapsedMillis(started), usefulMessage(error));
            }
        }

        private static URI probeUri(String endpoint) {
            try {
                URI search = URI.create(endpoint);
                String path = search.getPath() == null ? "" : search.getPath();
                if (path.endsWith("/search")) path = path.substring(0, path.length() - 7) + "/config";
                else path = path.endsWith("/") ? path + "config" : path + "/config";
                return new URI(search.getScheme(), search.getUserInfo(), search.getHost(),
                        search.getPort(), path, null, null);
            } catch (Exception error) {
                throw new IllegalArgumentException("invalid SearXNG endpoint", error);
            }
        }

        private static long elapsedMillis(long started) {
            return Math.max(0, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
        }
    }

    static final class DockerComposeStarter implements ComposeStarter {
        @Override
        public StartResult start(Config config) {
            Path compose = Path.of(config.composeFile()).toAbsolutePath().normalize();
            if (!Files.isRegularFile(compose)) {
                return StartResult.failed("compose file not found: " + compose);
            }
            List<String> candidates = new ArrayList<>();
            candidates.add(config.dockerCommand());
            if ("docker".equalsIgnoreCase(config.dockerCommand()) && isWindows()) {
                Path desktopDocker = Path.of("C:\\Program Files\\Docker\\Docker\\resources\\bin\\docker.exe");
                if (Files.isRegularFile(desktopDocker)) candidates.add(desktopDocker.toString());
            }
            String lastError = "Docker command was not available";
            for (String docker : candidates) {
                try {
                    ProcessBuilder builder = new ProcessBuilder(docker, "compose", "-f",
                            compose.toString(), "up", "-d");
                    builder.directory(compose.getParent().toFile());
                    builder.redirectErrorStream(true);
                    builder.redirectOutput(ProcessBuilder.Redirect.appendTo(new File(config.composeLog())));
                    Process process = builder.start();
                    if (!process.waitFor(config.composeCommandTimeout().toMillis(), TimeUnit.MILLISECONDS)) {
                        process.destroy();
                        return StartResult.failed("Docker Compose startup timed out");
                    }
                    if (process.exitValue() == 0) return StartResult.started();
                    lastError = "Docker Compose exited with code " + process.exitValue()
                            + "; see " + config.composeLog();
                } catch (Exception error) {
                    lastError = usefulMessage(error);
                }
            }
            return StartResult.failed(lastError);
        }

        private static boolean isWindows() {
            return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        }
    }
}
