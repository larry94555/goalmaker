package com.example.goalmaker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfSystemProperty(named = "goalmaker.live-docker-worker-test", matches = "true")
class DockerFetchWorkerLiveTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void runsHardenedContainerAndEnforcesFirewallIndependently() throws Exception {
        AtomicInteger privateCalls = new AtomicInteger();
        HttpServer privateServer = HttpServer.create(new InetSocketAddress("0.0.0.0", 0), 0);
        privateServer.createContext("/private", exchange -> {
            privateCalls.incrementAndGet();
            byte[] body = "private".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        privateServer.start();
        FetchWorkerClient client = new FetchWorkerClient(mapper);
        FetchIsolationSettings isolation = DockerWorkerCommandTest.dockerSettings(true);
        try {
            Map<String, Object> publicPayload = client.fetch(Map.of(
                    "url", "https://example.com", "max_chars", 1_000), publicSettings(), isolation);
            assertTrue(String.valueOf(publicPayload.get("content")).contains("Example Domain"));

            ContainerInspection inspection = inspectWorker();
            JsonNode hostConfig = inspection.hostConfig();
            assertTrue(hostConfig.path("ReadonlyRootfs").asBoolean());
            assertEquals(320L * 1_024 * 1_024, hostConfig.path("Memory").asLong());
            assertEquals(500_000_000L, hostConfig.path("NanoCpus").asLong());
            assertEquals(32, hostConfig.path("PidsLimit").asInt());
            assertTrue(hostConfig.path("CapDrop").toString().contains("ALL"));
            assertTrue(hostConfig.path("CapAdd").toString().contains("NET_ADMIN"));
            assertTrue(hostConfig.path("CapAdd").toString().contains("SETUID"));
            assertTrue(hostConfig.path("CapAdd").toString().contains("SETGID"));
            assertTrue(hostConfig.path("CapAdd").toString().contains("SETPCAP"));
            assertTrue(hostConfig.path("SecurityOpt").toString().contains("no-new-privileges:true"));
            assertTrue(hostConfig.path("Tmpfs").has("/tmp"));
            String processStatus = workerProcessStatus(inspection.id());
            assertTrue(processStatus.matches("(?s).*Uid:\\s+65532\\s+65532\\s+65532\\s+65532.*"));
            assertTrue(processStatus.matches("(?s).*CapEff:\\s+0+.*"));
            assertTrue(processStatus.matches("(?s).*NoNewPrivs:\\s+1.*"));
            assertRootFilesystemReadOnly(inspection.id());

            int port = privateServer.getAddress().getPort();
            FetchSettings deliberatelyPermissive = new FetchSettings(1, 0, 1_024, 0, 1_000,
                    true, Set.of(port), 5, 2, false, 1, 1, 0,
                    1_024, 0, 0, 0, 1, 1);
            assertThrows(IllegalStateException.class, () -> client.fetch(Map.of(
                    "url", "http://host.docker.internal:" + port + "/private"),
                    deliberatelyPermissive, isolation));
            assertEquals(0, privateCalls.get());

            Map<String, Object> status = client.status(isolation);
            assertEquals("docker", status.get("mode"));
            assertEquals(1, ((Number) status.get("live_workers")).intValue());
            assertTrue(((Number) status.get("docker_builds")).intValue() >= 0);
        } finally {
            client.close();
            privateServer.stop(0);
        }
    }

    private static FetchSettings publicSettings() {
        return new FetchSettings(1, 0, 1_048_576, 2, 1_000, false, Set.of(80, 443),
                20, 6, true, 5, 1, 0, 524_288, 2, 60, 10, 5, 10);
    }

    private ContainerInspection inspectWorker() throws Exception {
        String docker = dockerExecutable();
        Process list = new ProcessBuilder(docker, "ps", "--filter",
                "label=com.example.goalmaker.component=fetch-worker", "--format", "{{.ID}}")
                .redirectErrorStream(true).start();
        String id = new String(list.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        assertTrue(list.waitFor(30, TimeUnit.SECONDS) && list.exitValue() == 0);
        assertTrue(!id.isBlank(), "fetch-worker container was not running");
        Process inspect = new ProcessBuilder(docker, "inspect", id.split("\\R")[0],
                "--format", "{{json .HostConfig}}")
                .redirectErrorStream(true).start();
        String json = new String(inspect.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        assertTrue(inspect.waitFor(30, TimeUnit.SECONDS) && inspect.exitValue() == 0, json);
        return new ContainerInspection(id.split("\\R")[0], mapper.readTree(json));
    }

    private static String workerProcessStatus(String id) throws Exception {
        String script = "for status in /proc/[0-9]*/status; do "
                + "if grep -q '^Name:[[:space:]]*java' \"$status\"; then cat \"$status\"; fi; done";
        Process process = new ProcessBuilder(dockerExecutable(), "exec", id, "sh", "-c", script)
                .redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0, output);
        assertTrue(!output.isBlank(), "Java process status was not found");
        return output;
    }

    private static void assertRootFilesystemReadOnly(String id) throws Exception {
        Process process = new ProcessBuilder(dockerExecutable(), "exec", id, "touch", "/root-write-probe")
                .redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(30, TimeUnit.SECONDS));
        assertTrue(process.exitValue() != 0, "container root filesystem was writable: " + output);
    }

    private static String dockerExecutable() {
        String path = System.getenv("PATH");
        if (path != null) {
            for (String directory : path.split(java.io.File.pathSeparator)) {
                Path candidate = Path.of(directory, isWindows() ? "docker.exe" : "docker");
                if (Files.isRegularFile(candidate)) return candidate.toString();
            }
        }
        Path desktop = Path.of("C:\\Program Files\\Docker\\Docker\\resources\\bin\\docker.exe");
        return Files.isRegularFile(desktop) ? desktop.toString() : "docker";
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
    }

    private record ContainerInspection(String id, JsonNode hostConfig) {}
}
