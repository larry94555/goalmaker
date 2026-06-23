package com.example.goalmaker;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DockerWorkerCommandTest {
    @Test
    void createsHardenedContainerCommandWithoutHostMounts() {
        FetchIsolationSettings settings = dockerSettings(false);

        List<String> command = DockerWorkerCommand.create("docker", settings);

        assertTrue(command.contains("--read-only"));
        assertTrue(command.contains("--network=bridge"));
        assertTrue(command.contains("--sysctl=net.ipv6.conf.all.disable_ipv6=1"));
        assertTrue(command.contains("--cap-drop=ALL"));
        assertTrue(command.contains("--cap-add=NET_ADMIN"));
        assertTrue(command.contains("--cap-add=SETUID"));
        assertTrue(command.contains("--cap-add=SETGID"));
        assertTrue(command.contains("--cap-add=SETPCAP"));
        assertTrue(command.contains("--security-opt=no-new-privileges:true"));
        assertTrue(command.contains("--memory=320m"));
        assertTrue(command.contains("--memory-swap=320m"));
        assertTrue(command.contains("--cpus=0.5"));
        assertTrue(command.contains("--pids-limit=32"));
        assertTrue(command.contains("--tmpfs=/tmp:rw,noexec,nosuid,nodev,size=16m"));
        assertTrue(command.contains("--ulimit=nofile=128:128"));
        assertTrue(command.contains("--env=GOALMAKER_WORKER_HEAP_MB=128"));
        assertFalse(command.stream().anyMatch(value -> value.equals("--volume") || value.startsWith("--volume=")));
    }

    @Test
    void rejectsUnknownWorkerMode() {
        assertThrows(IllegalArgumentException.class, () -> new FetchIsolationSettings(
                "unknown", 1, 128, 1, 30, 10_000, FetchWorkerMain.class.getName(),
                "docker", "image", false, "Dockerfile", ".", "build.log",
                600, 320, 0.5, 32, 16));
    }

    static FetchIsolationSettings dockerSettings(boolean autoBuild) {
        return new FetchIsolationSettings("docker", 1, 128, 1, 30, 1_048_576,
                FetchWorkerMain.class.getName(), "docker", "goalmaker-fetch-worker:local", autoBuild,
                "docker/fetch-worker/Dockerfile", ".", "fetch-worker-docker-build.log",
                600, 320, 0.5, 32, 16);
    }
}
