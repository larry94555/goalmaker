package com.example.goalmaker;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Component
public class LlamaServerManager {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(LlamaServerManager.class);

    @Value("${llama.manage-server:true}") private boolean manageServer;
    @Value("${llama.binary:}") private String binary;
    @Value("${llama.profile:small}") private String profile;
    @Value("${llama.hf-model:}") private String hfModel;
    @Value("${llama.model-path:}") private String modelPath;
    @Value("${llama.alias:qwen2.5-3b-instruct}") private String alias;
    @Value("${llama.host:0.0.0.0}") private String host;
    @Value("${llama.port:8081}") private int port;
    @Value("${llama.client-host:127.0.0.1}") private String clientHost;
    @Value("${llama.ctx-size:0}") private int ctxSize;
    @Value("${llama.gpu-layers:-1}") private int gpuLayers;
    @Value("${llama.threads:0}") private int threads;
    @Value("${llama.parallel:1}") private int parallel;
    @Value("${llama.extra-args:}") private String extraArgs;
    @Value("${llama.auto-restart:true}") private boolean autoRestart;
    @Value("${llama.health-interval-seconds:15}") private int healthInterval;
    @Value("${llama.cache-reuse:256}") private int cacheReuse;
    @Value("${llama.draft-hf-model:}") private String draftHf;
    @Value("${llama.draft-model-path:}") private String draftPath;
    @Value("${llama.draft-tokens:16}") private int draftTokens;
    @Value("${llama.draft-gpu-layers:-1}") private int draftGpuLayers;

    private final HttpClient http = HttpClient.newHttpClient();
    private volatile Process proc;
    private volatile boolean shuttingDown;
    private List<String> command;
    private Thread watchdog;

    @PostConstruct
    public void start() {
        if (!manageServer) {
            log.info("[llama] manage-server=false; expecting an external llama-server on port {}", port);
            return;
        }
        command = buildCommand();
        log.info("[llama] launching: {}", String.join(" ", command));
        launch();
        waitUntilReady();
        if (healthy()) {
            startWatchdog();
        } else {
            log.warn("[llama] not healthy yet; watchdog NOT started (avoid restart-storm during model download).");
        }
    }

    private String resolveBinary() {
        if (binary != null && !binary.isBlank()) return binary.trim();
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        return windows ? "llama-server.exe" : "llama-server";
    }

    private List<String> buildCommand() {
        int ctx = ctxSize > 0 ? ctxSize : 8192;
        int ngl = gpuLayers >= 0 ? gpuLayers : 0;
        int workerThreads = threads > 0 ? threads : Runtime.getRuntime().availableProcessors();
        List<String> cmd = new ArrayList<>();
        cmd.add(resolveBinary());
        if (!modelPath.isBlank()) {
            cmd.add("-m");
            cmd.add(modelPath);
        } else {
            cmd.add("-hf");
            cmd.add(hfModel.isBlank() ? profileModel(profile) : hfModel);
        }
        cmd.add("--host"); cmd.add(host);
        cmd.add("--port"); cmd.add(String.valueOf(port));
        cmd.add("-c"); cmd.add(String.valueOf(ctx));
        cmd.add("-t"); cmd.add(String.valueOf(workerThreads));
        cmd.add("-ngl"); cmd.add(String.valueOf(ngl));
        cmd.add("--parallel"); cmd.add(String.valueOf(Math.max(1, parallel)));
        cmd.add("--alias"); cmd.add(alias);
        cmd.add("--jinja");
        if (cacheReuse > 0) {
            cmd.add("--cache-reuse");
            cmd.add(String.valueOf(cacheReuse));
        }
        if (!draftPath.isBlank() || !draftHf.isBlank()) {
            if (!draftPath.isBlank()) { cmd.add("-md"); cmd.add(draftPath); }
            else { cmd.add("-hfd"); cmd.add(draftHf); }
            cmd.add("--draft-max"); cmd.add(String.valueOf(Math.max(1, draftTokens)));
            if (draftGpuLayers >= 0) { cmd.add("-ngld"); cmd.add(String.valueOf(draftGpuLayers)); }
        }
        if (extraArgs != null && !extraArgs.isBlank()) {
            for (String arg : extraArgs.trim().split("\\s+")) cmd.add(arg);
        }
        return cmd;
    }

    private String profileModel(String value) {
        return switch (value == null ? "" : value.toLowerCase(Locale.ROOT)) {
            case "medium" -> "Qwen/Qwen2.5-7B-Instruct-GGUF:Q4_K_M";
            case "large" -> "bartowski/Meta-Llama-3.1-8B-Instruct-GGUF:Q4_K_M";
            default -> "Qwen/Qwen2.5-3B-Instruct-GGUF:Q4_K_M";
        };
    }

    private void launch() {
        try {
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.redirectOutput(new File("llama-server.log"));
            builder.redirectError(new File("llama-server.log"));
            proc = builder.start();
            log.info("[llama] started on port {} (profile={}, parallel={}, logs -> llama-server.log)",
                    port, profile, Math.max(1, parallel));
        } catch (IOException e) {
            log.warn("[llama] failed to start: {}", e.getMessage());
        }
    }

    private boolean healthy() {
        try {
            HttpResponse<String> response = http.send(HttpRequest.newBuilder(
                    URI.create("http://" + clientHost + ":" + port + "/health")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    private void waitUntilReady() {
        for (int i = 0; i < 600; i++) {
            if (healthy()) {
                log.info("[llama] ready.");
                return;
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        log.warn("[llama] not ready after 600s; check llama-server.log");
    }

    private void startWatchdog() {
        if (!autoRestart) return;
        watchdog = new Thread(() -> {
            while (!shuttingDown) {
                try {
                    Thread.sleep(Math.max(5, healthInterval) * 1000L);
                } catch (InterruptedException e) {
                    return;
                }
                if (shuttingDown) return;
                if (proc == null || !proc.isAlive() || !healthy()) {
                    log.warn("[llama] watchdog: server unhealthy; restarting...");
                    launch();
                    waitUntilReady();
                }
            }
        }, "llama-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
        log.info("[llama] watchdog on (checks every {}s).", Math.max(5, healthInterval));
    }

    @PreDestroy
    public void stop() {
        shuttingDown = true;
        if (watchdog != null) watchdog.interrupt();
        if (proc != null && proc.isAlive()) {
            proc.destroy();
            log.info("[llama] stopped.");
        }
    }
}
