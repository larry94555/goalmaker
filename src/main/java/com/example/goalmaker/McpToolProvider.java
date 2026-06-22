package com.example.goalmaker;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

@Component
public class McpToolProvider {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(McpToolProvider.class);
    private static final String DEFAULT_PROTOCOL_VERSION = "2024-11-05";

    private final ObjectMapper mapper;
    private final List<Server> servers = new ArrayList<>();
    private final List<ToolDefinition> tools = new ArrayList<>();

    @Value("${mcp.config:mcp.json}") private String configFile = "mcp.json";
    @Value("${tools.timeout-seconds:60}") private int timeoutSeconds = 60;

    public McpToolProvider(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @PostConstruct
    public void start() {
        start(Path.of(configFile));
    }

    @SuppressWarnings("unchecked")
    synchronized void start(Path config) {
        if (!Files.exists(config)) {
            log.info("[mcp] no {} found; MCP tools are off", config);
            return;
        }
        try {
            Map<String, Object> root = mapper.readValue(Files.readAllBytes(config), Map.class);
            Map<String, Object> definitions = root.get("mcpServers") instanceof Map<?, ?> map
                    ? (Map<String, Object>) map : Map.of();
            for (Map.Entry<String, Object> entry : definitions.entrySet()) {
                if (entry.getValue() instanceof Map<?, ?> server) {
                    startServer(entry.getKey(), (Map<String, Object>) server, config.toAbsolutePath().getParent());
                }
            }
            log.info("[mcp] registered {} tool(s) from {} server(s)", tools.size(), servers.size());
        } catch (Exception error) {
            log.warn("[mcp] could not load {}: {}", config, error.getMessage());
        }
    }

    public synchronized List<ToolDefinition> tools() {
        return List.copyOf(tools);
    }

    @SuppressWarnings("unchecked")
    private void startServer(String name, Map<String, Object> config, Path configDirectory) {
        try {
            List<String> command = new ArrayList<>();
            command.add(String.valueOf(config.get("command")));
            if (config.get("args") instanceof List<?> args) args.forEach(arg -> command.add(String.valueOf(arg)));
            ProcessBuilder builder = new ProcessBuilder(command);
            if (config.get("env") instanceof Map<?, ?> env) {
                env.forEach((key, value) -> builder.environment().put(String.valueOf(key), String.valueOf(value)));
            }
            if (config.get("cwd") != null) builder.directory(resolve(configDirectory, config.get("cwd")).toFile());
            Path logs = configDirectory.resolve("logs");
            Files.createDirectories(logs);
            builder.redirectError(logs.resolve("mcp-" + sanitize(name) + ".log").toFile());
            Server server = new Server(name, builder.start());
            servers.add(server);

            String protocol = String.valueOf(config.getOrDefault("protocolVersion", DEFAULT_PROTOCOL_VERSION));
            server.request("initialize", Map.of(
                    "protocolVersion", protocol,
                    "capabilities", Map.of(),
                    "clientInfo", Map.of("name", "goalmaker", "version", "0.0.1")));
            server.notify("notifications/initialized", Map.of());
            Map<String, Object> response = server.request("tools/list", Map.of());
            Map<String, Object> result = response.get("result") instanceof Map<?, ?> map
                    ? (Map<String, Object>) map : Map.of();
            List<Map<String, Object>> listed = result.get("tools") instanceof List<?> list
                    ? (List<Map<String, Object>>) (List<?>) list : List.of();
            for (Map<String, Object> tool : listed) {
                String originalName = String.valueOf(tool.get("name"));
                String exposedName = "mcp_" + sanitize(name) + "_" + sanitize(originalName);
                Map<String, Object> schema = tool.get("inputSchema") instanceof Map<?, ?> map
                        ? (Map<String, Object>) map : emptySchema();
                String description = String.valueOf(tool.getOrDefault("description", ""));
                tools.add(new ToolDefinition(exposedName, "[MCP:" + name + "] " + description,
                        schema, "mcp:" + name, arguments -> server.callTool(originalName, arguments)));
                log.info("[mcp] {} -> tool {}", name, exposedName);
            }
        } catch (Exception error) {
            log.warn("[mcp] server {} failed: {}", name, error.getMessage());
        }
    }

    private static Path resolve(Path base, Object value) {
        Path path = Path.of(String.valueOf(value));
        return path.isAbsolute() ? path : base.resolve(path).normalize();
    }

    private static String sanitize(String value) {
        return value.replaceAll("[^A-Za-z0-9_-]", "_");
    }

    private static Map<String, Object> emptySchema() {
        return Map.of("type", "object", "properties", Map.of());
    }

    @PreDestroy
    public synchronized void stop() {
        servers.forEach(Server::close);
        servers.clear();
    }

    private final class Server {
        private final String name;
        private final Process process;
        private final BufferedWriter output;
        private final BufferedReader input;
        private final ExecutorService reader;
        private int nextId = 1;

        Server(String name, Process process) {
            this.name = name;
            this.process = process;
            output = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
            input = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
            reader = Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "mcp-" + name + "-reader");
                thread.setDaemon(true);
                return thread;
            });
        }

        synchronized Map<String, Object> request(String method, Map<String, Object> params) throws Exception {
            int id = nextId++;
            write(Map.of("jsonrpc", "2.0", "id", id, "method", method, "params", params));
            Future<Map<String, Object>> response = reader.submit(() -> readResponse(id));
            return response.get(Math.max(1, timeoutSeconds), TimeUnit.SECONDS);
        }

        synchronized void notify(String method, Map<String, Object> params) throws Exception {
            write(Map.of("jsonrpc", "2.0", "method", method, "params", params));
        }

        private void write(Map<String, Object> message) throws Exception {
            output.write(mapper.writeValueAsString(message));
            output.newLine();
            output.flush();
        }

        @SuppressWarnings("unchecked")
        private Map<String, Object> readResponse(int id) throws Exception {
            String line;
            while ((line = input.readLine()) != null) {
                try {
                    Map<String, Object> response = mapper.readValue(line, Map.class);
                    if (response.get("id") instanceof Number number && number.intValue() == id) return response;
                } catch (Exception ignored) {
                    // Protocol stdout should be JSON-RPC, but skip accidental non-JSON output.
                }
            }
            throw new IllegalStateException("MCP server " + name + " closed while waiting for " + id);
        }

        @SuppressWarnings("unchecked")
        String callTool(String toolName, Map<String, Object> arguments) throws Exception {
            Map<String, Object> response = request("tools/call", Map.of(
                    "name", toolName, "arguments", arguments == null ? Map.of() : arguments));
            if (response.get("error") != null) throw new IllegalStateException(String.valueOf(response.get("error")));
            Map<String, Object> result = response.get("result") instanceof Map<?, ?> map
                    ? (Map<String, Object>) map : Map.of();
            StringBuilder text = new StringBuilder();
            if (result.get("content") instanceof List<?> content) {
                for (Object item : content) {
                    if (item instanceof Map<?, ?> part && "text".equals(part.get("type"))) {
                        if (!text.isEmpty()) text.append("\n");
                        text.append(part.get("text"));
                    }
                }
            }
            return text.isEmpty() ? mapper.writeValueAsString(result) : text.toString();
        }

        void close() {
            try {
                output.close();
            } catch (Exception ignored) {
                // Continue shutdown even when the server already closed its input.
            }
            process.destroy();
            try {
                if (!process.waitFor(2, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    process.waitFor(2, TimeUnit.SECONDS);
                }
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
            try {
                input.close();
            } catch (Exception ignored) {
                // The process may already have closed stdout.
            }
            reader.shutdownNow();
        }
    }
}
