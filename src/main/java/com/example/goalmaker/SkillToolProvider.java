package com.example.goalmaker;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

@Component
public class SkillToolProvider {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(SkillToolProvider.class);

    private final ObjectMapper mapper;
    private final List<ToolDefinition> tools = new ArrayList<>();

    @Value("${skills.dir:skills}") private String skillsDir = "skills";
    @Value("${tools.timeout-seconds:60}") private int defaultTimeoutSeconds = 60;

    public SkillToolProvider(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @PostConstruct
    public void load() {
        reload(Path.of(skillsDir));
    }

    /** Re-scans the configured skills directory so files added after startup are picked up. */
    public synchronized void reload() {
        reload(Path.of(skillsDir));
    }

    synchronized void reload(Path directory) {
        tools.clear();
        if (!Files.isDirectory(directory)) {
            log.info("[skills] no {} directory; skill tools are off", directory);
            return;
        }
        try (Stream<Path> files = Files.walk(directory)) {
            files.filter(path -> path.getFileName().toString().equalsIgnoreCase("SKILL.md"))
                    .sorted()
                    .forEach(this::loadSkill);
        } catch (Exception error) {
            log.warn("[skills] could not scan {}: {}", directory, error.getMessage());
        }
        log.info("[skills] registered {} skill tool(s)", tools.size());
    }

    public synchronized List<ToolDefinition> tools() {
        return List.copyOf(tools);
    }

    @SuppressWarnings("unchecked")
    private void loadSkill(Path file) {
        try {
            String markdown = Files.readString(file, StandardCharsets.UTF_8);
            FrontMatter parsed = parseFrontMatter(markdown);
            Map<String, Object> manifest = new Yaml().load(parsed.yaml());
            if (manifest == null) manifest = Map.of();
            String name = required(manifest, "name");
            String description = required(manifest, "description");
            Map<String, Object> parameters = manifest.get("parameters") instanceof Map<?, ?> schema
                    ? (Map<String, Object>) schema : emptySchema();
            Object command = manifest.get("command");
            int timeout = manifest.get("timeoutSeconds") instanceof Number number
                    ? number.intValue() : defaultTimeoutSeconds;
            String exposedName = "skill_" + sanitize(name);
            Path skillDirectory = file.getParent();
            ToolDefinition.Executor executor = command == null
                    ? arguments -> instructionResult(parsed.body(), arguments)
                    : arguments -> execute(command, skillDirectory, arguments, timeout);
            tools.add(new ToolDefinition(exposedName, "[Skill:" + name + "] " + description,
                    parameters, "skill:" + name, executor));
            log.info("[skills] {} -> tool {}", file, exposedName);
        } catch (Exception error) {
            log.warn("[skills] ignored {}: {}", file, error.getMessage());
        }
    }

    private String instructionResult(String body, Map<String, Object> arguments) throws Exception {
        return "Skill instructions:\n" + body.trim() + "\n\nArguments:\n"
                + mapper.writeValueAsString(arguments == null ? Map.of() : arguments);
    }

    private String execute(Object command, Path directory, Map<String, Object> arguments, int timeout)
            throws Exception {
        List<String> parts = commandParts(command);
        ProcessBuilder builder = new ProcessBuilder(parts).directory(directory.toFile()).redirectErrorStream(true);
        Process process = builder.start();
        try (OutputStream stdin = process.getOutputStream()) {
            mapper.writeValue(stdin, arguments == null ? Map.of() : arguments);
        }
        CompletableFuture<byte[]> output = CompletableFuture.supplyAsync(() -> {
            try {
                return process.getInputStream().readAllBytes();
            } catch (Exception error) {
                throw new RuntimeException(error);
            }
        });
        if (!process.waitFor(Math.max(1, timeout), TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("skill timed out after " + timeout + " seconds");
        }
        String text = new String(output.get(Duration.ofSeconds(5).toMillis(), TimeUnit.MILLISECONDS),
                StandardCharsets.UTF_8).trim();
        if (process.exitValue() != 0) {
            throw new IllegalStateException("skill exited " + process.exitValue() + ": " + text);
        }
        return text.isEmpty() ? "(skill completed with no output)" : text;
    }

    private static List<String> commandParts(Object command) {
        if (command instanceof List<?> list && !list.isEmpty()) {
            return list.stream().map(String::valueOf).toList();
        }
        String text = String.valueOf(command).trim();
        if (text.isEmpty()) throw new IllegalArgumentException("command must not be empty");
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        return windows ? List.of("cmd.exe", "/c", text) : List.of("sh", "-c", text);
    }

    private static FrontMatter parseFrontMatter(String markdown) {
        String normalized = markdown.replace("\r\n", "\n");
        if (!normalized.startsWith("---\n")) {
            throw new IllegalArgumentException("SKILL.md must begin with YAML front matter");
        }
        int end = normalized.indexOf("\n---\n", 4);
        if (end < 0) throw new IllegalArgumentException("SKILL.md front matter is not closed");
        return new FrontMatter(normalized.substring(4, end), normalized.substring(end + 5));
    }

    private static String required(Map<String, Object> manifest, String key) {
        String value = String.valueOf(manifest.getOrDefault(key, "")).trim();
        if (value.isEmpty()) throw new IllegalArgumentException(key + " is required");
        return value;
    }

    private static String sanitize(String value) {
        String safe = value.replaceAll("[^A-Za-z0-9_-]", "_");
        if (safe.isBlank()) throw new IllegalArgumentException("skill name has no usable characters");
        return safe;
    }

    private static Map<String, Object> emptySchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of());
        return schema;
    }

    private record FrontMatter(String yaml, String body) {}
}
