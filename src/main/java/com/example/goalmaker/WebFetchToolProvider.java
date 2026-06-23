package com.example.goalmaker;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class WebFetchToolProvider {
    @Value("${web.fetch.max-attempts:2}") private int maxAttempts = 2;
    @Value("${web.fetch.retry-delay-millis:250}") private long retryDelayMillis = 250;
    @Value("${web.fetch.max-response-bytes:8388608}") private int maxResponseBytes = 8_388_608;
    @Value("${web.fetch.max-redirects:5}") private int maxRedirects = 5;
    @Value("${web.fetch.default-max-chars:12000}") private int defaultMaxChars = 12_000;
    @Value("${web.fetch.allow-private-addresses:false}") private boolean allowPrivateAddresses;
    @Value("${web.fetch.allowed-ports:80,443}") private String allowedPorts = "80,443";
    @Value("${web.fetch.total-budget-seconds:40}") private int totalBudgetSeconds = 40;
    @Value("${web.fetch.max-http-requests:12}") private int maxHttpRequests = 12;
    @Value("${web.fetch.robots.enabled:true}") private boolean robotsEnabled = true;
    @Value("${web.fetch.robots.timeout-seconds:5}") private int robotsTimeoutSeconds = 5;
    @Value("${web.fetch.robots.max-attempts:1}") private int robotsMaxAttempts = 1;
    @Value("${web.fetch.robots.retry-delay-millis:250}") private long robotsRetryDelayMillis = 250;
    @Value("${web.fetch.robots.max-response-bytes:524288}") private int robotsMaxResponseBytes = 524_288;
    @Value("${web.fetch.robots.max-redirects:5}") private int robotsMaxRedirects = 5;
    @Value("${web.fetch.robots.cache-ttl-seconds:3600}") private int robotsCacheTtlSeconds = 3_600;
    @Value("${web.fetch.robots.cache-max-entries:200}") private int robotsCacheMaxEntries = 200;
    @Value("${web.fetch.pdf.max-pages:40}") private int pdfMaxPages = 40;
    @Value("${web.fetch.pdf.timeout-seconds:20}") private int pdfTimeoutSeconds = 20;
    @Value("${web.fetch.worker.enabled:true}") private boolean workerEnabled = true;
    @Value("${web.fetch.worker.mode:process}") private String workerMode = "process";
    @Value("${web.fetch.worker.pool-size:4}") private int workerPoolSize = 4;
    @Value("${web.fetch.worker.memory-mb:192}") private int workerMemoryMb = 192;
    @Value("${web.fetch.worker.active-processors:1}") private int workerActiveProcessors = 1;
    @Value("${web.fetch.worker.timeout-seconds:45}") private int workerTimeoutSeconds = 45;
    @Value("${web.fetch.worker.max-output-bytes:1048576}") private int workerMaxOutputBytes = 1_048_576;
    @Value("${web.fetch.worker.main-class:com.example.goalmaker.FetchWorkerMain}")
    private String workerMainClass = FetchWorkerMain.class.getName();
    @Value("${web.fetch.worker.docker.command:docker}") private String dockerCommand = "docker";
    @Value("${web.fetch.worker.docker.image:goalmaker-fetch-worker:local}")
    private String dockerImage = "goalmaker-fetch-worker:local";
    @Value("${web.fetch.worker.docker.auto-build:true}") private boolean dockerAutoBuild = true;
    @Value("${web.fetch.worker.docker.dockerfile:docker/fetch-worker/Dockerfile}")
    private String dockerfile = "docker/fetch-worker/Dockerfile";
    @Value("${web.fetch.worker.docker.context:.}") private String dockerContext = ".";
    @Value("${web.fetch.worker.docker.build-log:fetch-worker-docker-build.log}")
    private String dockerBuildLog = "fetch-worker-docker-build.log";
    @Value("${web.fetch.worker.docker.build-timeout-seconds:600}") private int dockerBuildTimeoutSeconds = 600;
    @Value("${web.fetch.worker.docker.memory-mb:384}") private int dockerMemoryMb = 384;
    @Value("${web.fetch.worker.docker.cpus:1.0}") private double dockerCpus = 1.0;
    @Value("${web.fetch.worker.docker.pids-limit:64}") private int dockerPidsLimit = 64;
    @Value("${web.fetch.worker.docker.tmpfs-mb:32}") private int dockerTmpfsMb = 32;

    private final ObjectMapper mapper;
    private final WebFetchEngine localEngine;
    private final FetchWorkerClient workers;

    public WebFetchToolProvider() {
        this(new ObjectMapper());
    }

    @Autowired
    public WebFetchToolProvider(ObjectMapper mapper) {
        this.mapper = mapper;
        this.localEngine = new WebFetchEngine(mapper, new RobotsPolicy());
        this.workers = new FetchWorkerClient(mapper);
    }

    public List<ToolDefinition> tools() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("url", Map.of("type", "string",
                "description", "Absolute public http(s) URL returned by web_search."));
        properties.put("max_chars", Map.of("type", "integer",
                "description", "Maximum readable characters to return (1000-20000).",
                "minimum", 1_000, "maximum", 20_000));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("url"));
        return List.of(new ToolDefinition(
                "web_fetch",
                "Fetch a robots-permitted public HTML, text, or PDF document in an isolated worker and return readable text with source provenance.",
                schema,
                "builtin:web_fetch",
                true,
                this::fetch));
    }

    private String fetch(Map<String, Object> arguments) throws Exception {
        return mapper.writeValueAsString(fetchPayload(arguments));
    }

    Map<String, Object> fetchPayload(Map<String, Object> arguments) throws Exception {
        FetchSettings fetchSettings = fetchSettings();
        Map<String, Object> payload;
        if (workerEnabled) {
            FetchIsolationSettings isolation = isolationSettings();
            payload = workers.fetch(arguments, fetchSettings, isolation);
            payload.put("fetch_isolation", isolationProvenance(isolation, workers.status(isolation)));
        } else {
            payload = localEngine.fetchPayload(arguments, fetchSettings);
            payload.put("fetch_isolation", Map.of("mode", "in-process", "enabled", false));
        }
        return payload;
    }

    private FetchSettings fetchSettings() {
        return new FetchSettings(maxAttempts, retryDelayMillis, maxResponseBytes, maxRedirects,
                defaultMaxChars, allowPrivateAddresses, parsePorts(allowedPorts), totalBudgetSeconds,
                maxHttpRequests, robotsEnabled, robotsTimeoutSeconds, robotsMaxAttempts,
                robotsRetryDelayMillis, robotsMaxResponseBytes, robotsMaxRedirects,
                robotsCacheTtlSeconds, robotsCacheMaxEntries, pdfMaxPages, pdfTimeoutSeconds);
    }

    private FetchIsolationSettings isolationSettings() {
        return new FetchIsolationSettings(workerMode, workerPoolSize, workerMemoryMb, workerActiveProcessors,
                workerTimeoutSeconds, workerMaxOutputBytes, workerMainClass, dockerCommand, dockerImage,
                dockerAutoBuild, dockerfile, dockerContext, dockerBuildLog, dockerBuildTimeoutSeconds,
                dockerMemoryMb, dockerCpus, dockerPidsLimit, dockerTmpfsMb);
    }

    private static Map<String, Object> isolationProvenance(FetchIsolationSettings settings,
                                                           Map<String, Object> status) {
        Map<String, Object> provenance = new LinkedHashMap<>();
        provenance.put("mode", settings.docker() ? "worker-docker" : "worker-process");
        provenance.put("enabled", true);
        provenance.put("pool_size", settings.poolSize());
        provenance.put("heap_limit_mb", settings.memoryMb());
        provenance.put("active_processors", settings.activeProcessors());
        provenance.put("wall_clock_seconds", settings.timeoutSeconds());
        provenance.put("output_limit_bytes", settings.maxOutputBytes());
        if (settings.docker()) {
            provenance.put("total_memory_limit_mb", settings.dockerMemoryMb());
            provenance.put("cpu_limit", settings.dockerCpus());
            provenance.put("pids_limit", settings.dockerPidsLimit());
            provenance.put("tmpfs_mb", settings.dockerTmpfsMb());
            provenance.put("read_only_root", true);
            provenance.put("capabilities_dropped", true);
            provenance.put("no_new_privileges", true);
            provenance.put("egress_firewall", "dns-and-public-http-https-only");
        }
        provenance.put("status", status);
        return provenance;
    }

    private static Set<Integer> parsePorts(String configured) {
        if (configured == null || configured.isBlank()) return Set.of();
        Set<Integer> ports = new LinkedHashSet<>();
        for (String token : configured.split(",")) {
            int port;
            try {
                port = Integer.parseInt(token.trim());
            } catch (NumberFormatException error) {
                throw new IllegalArgumentException("web.fetch.allowed-ports contains an invalid port", error);
            }
            if (port < 1 || port > 65_535) {
                throw new IllegalArgumentException("web.fetch.allowed-ports must contain ports from 1 to 65535");
            }
            ports.add(port);
        }
        return Set.copyOf(ports);
    }

    @PreDestroy
    void close() {
        workers.close();
    }
}
