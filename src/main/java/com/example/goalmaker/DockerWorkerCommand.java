package com.example.goalmaker;

import java.util.ArrayList;
import java.util.List;

final class DockerWorkerCommand {
    private DockerWorkerCommand() {}

    static List<String> create(String docker, FetchIsolationSettings settings) {
        List<String> command = new ArrayList<>();
        command.add(docker);
        command.add("run");
        command.add("--rm");
        command.add("--interactive");
        command.add("--init");
        command.add("--network=bridge");
        command.add("--sysctl=net.ipv6.conf.all.disable_ipv6=1");
        command.add("--read-only");
        command.add("--tmpfs=/tmp:rw,noexec,nosuid,nodev,size=" + settings.dockerTmpfsMb() + "m");
        command.add("--cap-drop=ALL");
        command.add("--cap-add=NET_ADMIN");
        command.add("--cap-add=SETUID");
        command.add("--cap-add=SETGID");
        command.add("--cap-add=SETPCAP");
        command.add("--security-opt=no-new-privileges:true");
        command.add("--memory=" + settings.dockerMemoryMb() + "m");
        command.add("--memory-swap=" + settings.dockerMemoryMb() + "m");
        command.add("--cpus=" + settings.dockerCpus());
        command.add("--pids-limit=" + settings.dockerPidsLimit());
        command.add("--ulimit=nofile=128:128");
        command.add("--stop-timeout=1");
        command.add("--label=com.example.goalmaker.component=fetch-worker");
        command.add("--env=GOALMAKER_WORKER_HEAP_MB=" + settings.memoryMb());
        command.add("--env=GOALMAKER_WORKER_ACTIVE_PROCESSORS=" + settings.activeProcessors());
        command.add(settings.dockerImage());
        return List.copyOf(command);
    }
}
