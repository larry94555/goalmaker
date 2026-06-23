package com.example.goalmaker;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

final class DockerWorkerManager {
    private final AtomicLong builds = new AtomicLong();
    private volatile String preparedFingerprint = "";
    private volatile String dockerExecutable = "";
    private volatile String lastBuildAt = "";

    synchronized void prepare(FetchIsolationSettings settings) throws Exception {
        if (!settings.docker()) return;
        String fingerprint = settings.fingerprint() + ":" + settings.dockerAutoBuild() + ":"
                + settings.dockerfile() + ":" + settings.dockerContext() + ":" + sourceFingerprint(settings);
        if (fingerprint.equals(preparedFingerprint)) return;
        dockerExecutable = resolveDocker(settings.dockerCommand());
        if (settings.dockerAutoBuild()) {
            String sourceFingerprint = sourceFingerprint(settings);
            if (!sourceFingerprint.equals(imageFingerprint(settings.dockerImage()))) {
                build(settings, sourceFingerprint);
            }
        }
        else inspectImage(settings.dockerImage());
        preparedFingerprint = fingerprint;
    }

    String executable(FetchIsolationSettings settings) throws Exception {
        prepare(settings);
        return dockerExecutable;
    }

    Map<String, Object> status() {
        return Map.of(
                "docker_builds", builds.get(),
                "docker_last_build_at", lastBuildAt,
                "docker_executable", dockerExecutable);
    }

    private void inspectImage(String image) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(dockerExecutable, "image", "inspect", image);
        configureEnvironment(builder);
        Process process = builder.redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("timed out checking Docker fetch-worker image " + image);
        }
        if (process.exitValue() != 0) {
            throw new IllegalStateException("Docker fetch-worker image " + image
                    + " is unavailable; build it or enable web.fetch.worker.docker.auto-build");
        }
    }

    private void build(FetchIsolationSettings settings, String sourceFingerprint) throws Exception {
        Path log = Path.of(settings.dockerBuildLog()).toAbsolutePath().normalize();
        if (log.getParent() != null) Files.createDirectories(log.getParent());
        List<String> command = List.of(dockerExecutable, "build", "--pull=false",
                "--build-arg", "GOALMAKER_SOURCE_FINGERPRINT=" + sourceFingerprint,
                "--file", settings.dockerfile(), "--tag", settings.dockerImage(), settings.dockerContext());
        ProcessBuilder builder = new ProcessBuilder(command);
        configureEnvironment(builder);
        builder.redirectErrorStream(true);
        builder.redirectOutput(log.toFile());
        Process process = builder.start();
        if (!process.waitFor(settings.dockerBuildTimeoutSeconds(), TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("Docker fetch-worker image build timed out; see " + log);
        }
        if (process.exitValue() != 0) {
            throw new IllegalStateException("Docker fetch-worker image build failed with exit code "
                    + process.exitValue() + "; see " + log);
        }
        builds.incrementAndGet();
        lastBuildAt = Instant.now().toString();
    }

    private String imageFingerprint(String image) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(dockerExecutable, "image", "inspect", image,
                "--format", "{{ index .Config.Labels \"com.example.goalmaker.source\" }}");
        configureEnvironment(builder);
        Process process = builder.redirectError(ProcessBuilder.Redirect.DISCARD).start();
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            return "";
        }
        if (process.exitValue() != 0) return "";
        return new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).trim();
    }

    private static String sourceFingerprint(FetchIsolationSettings settings) throws Exception {
        Path root = Path.of(settings.dockerContext()).toAbsolutePath().normalize();
        List<Path> files = new ArrayList<>();
        addIfFile(files, root.resolve("pom.xml"));
        addIfFile(files, root.resolve(settings.dockerfile()));
        addIfFile(files, root.resolve("docker/fetch-worker/entrypoint.sh"));
        Path source = root.resolve("src/main");
        if (Files.isDirectory(source)) {
            try (java.util.stream.Stream<Path> paths = Files.walk(source)) {
                paths.filter(Files::isRegularFile).forEach(files::add);
            }
        }
        files.sort(Comparator.comparing(path -> root.relativize(path).toString()));
        if (files.isEmpty()) throw new IllegalStateException("Docker worker build context has no source files");
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (Path file : files) {
            digest.update(root.relativize(file).toString().replace('\\', '/').getBytes(
                    java.nio.charset.StandardCharsets.UTF_8));
            digest.update(Files.readAllBytes(file));
        }
        return java.util.HexFormat.of().formatHex(digest.digest());
    }

    private static void addIfFile(List<Path> files, Path path) {
        if (Files.isRegularFile(path)) files.add(path);
    }

    void configureEnvironment(ProcessBuilder builder) {
        if (dockerExecutable == null || dockerExecutable.isBlank()) return;
        Path executable = Path.of(dockerExecutable).toAbsolutePath().normalize();
        Path directory = executable.getParent();
        if (directory == null) return;
        String path = builder.environment().getOrDefault("PATH", "");
        String prefix = directory.toString();
        if (!path.toLowerCase(java.util.Locale.ROOT).contains(prefix.toLowerCase(java.util.Locale.ROOT))) {
            builder.environment().put("PATH", prefix + java.io.File.pathSeparator + path);
        }
    }

    private static String resolveDocker(String configured) throws Exception {
        List<String> candidates = new ArrayList<>();
        if (configured != null && !configured.isBlank()) candidates.add(configured.trim());
        if (isWindows()) {
            Path desktop = Path.of("C:\\Program Files\\Docker\\Docker\\resources\\bin\\docker.exe");
            if (Files.isRegularFile(desktop) && !candidates.contains(desktop.toString())) {
                candidates.add(desktop.toString());
            }
        }
        String lastError = "Docker command was not available";
        for (String candidate : candidates) {
            try {
                Process process = new ProcessBuilder(candidate, "version", "--format", "{{.Server.Os}}")
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .redirectError(ProcessBuilder.Redirect.DISCARD)
                        .start();
                if (process.waitFor(15, TimeUnit.SECONDS) && process.exitValue() == 0) return candidate;
                if (process.isAlive()) process.destroyForcibly();
                lastError = candidate + " could not reach the Docker engine";
            } catch (IOException error) {
                lastError = candidate + ": " + error.getMessage();
            }
        }
        throw new IllegalStateException(lastError);
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
    }
}
