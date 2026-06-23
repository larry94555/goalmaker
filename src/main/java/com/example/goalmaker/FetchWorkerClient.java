package com.example.goalmaker;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

final class FetchWorkerClient implements Closeable {
    private final ObjectMapper mapper;
    private final Object lock = new Object();
    private final BlockingQueue<WorkerProcess> idle = new LinkedBlockingQueue<>();
    private final Set<WorkerProcess> workers = new HashSet<>();
    private volatile boolean closed;

    FetchWorkerClient(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    Map<String, Object> fetch(Map<String, Object> arguments, FetchSettings fetchSettings,
                              FetchIsolationSettings isolation) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(isolation.timeoutSeconds());
        WorkerProcess worker = acquire(isolation, deadline);
        boolean reusable = false;
        try {
            FetchWorkerProtocol.Request request = new FetchWorkerProtocol.Request(arguments, fetchSettings);
            FetchWorkerProtocol.Response response = worker.exchange(mapper.writeValueAsString(request),
                    isolation.maxOutputBytes(), remainingMillis(deadline));
            reusable = true;
            if (response.error() != null && !response.error().isBlank()) {
                throw new IllegalStateException(response.error());
            }
            return new java.util.LinkedHashMap<>(response.payload());
        } catch (IllegalStateException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalStateException(usefulMessage(error), error);
        } finally {
            if (reusable && worker.alive()) release(worker);
            else discard(worker);
        }
    }

    private WorkerProcess acquire(FetchIsolationSettings settings, long deadline) throws Exception {
        while (true) {
            if (closed) throw new IllegalStateException("fetch worker pool is closed");
            WorkerProcess available = idle.poll();
            if (available != null) {
                if (available.alive() && available.matches(settings)) return available;
                discard(available);
                continue;
            }
            synchronized (lock) {
                workers.removeIf(worker -> !worker.alive());
                if (workers.size() < settings.poolSize()) {
                    WorkerProcess created = WorkerProcess.start(settings, mapper);
                    workers.add(created);
                    return created;
                }
            }
            long remaining = remainingMillis(deadline);
            if (remaining <= 0) throw new IllegalStateException("timed out waiting for a fetch worker");
            available = idle.poll(remaining, TimeUnit.MILLISECONDS);
            if (available == null) throw new IllegalStateException("timed out waiting for a fetch worker");
            if (available.alive() && available.matches(settings)) return available;
            discard(available);
        }
    }

    private void release(WorkerProcess worker) {
        if (!closed && worker.alive()) idle.offer(worker);
        else discard(worker);
    }

    private void discard(WorkerProcess worker) {
        if (worker == null) return;
        idle.remove(worker);
        synchronized (lock) {
            workers.remove(worker);
        }
        worker.close();
    }

    @Override
    public void close() {
        closed = true;
        List<WorkerProcess> snapshot;
        synchronized (lock) {
            snapshot = List.copyOf(workers);
            workers.clear();
        }
        idle.clear();
        snapshot.forEach(WorkerProcess::close);
    }

    int workerCount() {
        synchronized (lock) {
            return workers.size();
        }
    }

    private static long remainingMillis(long deadline) {
        long remaining = deadline - System.nanoTime();
        return remaining <= 0 ? 0 : Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining));
    }

    private static String usefulMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }

    private static final class WorkerProcess implements Closeable {
        private final Process process;
        private final OutputStream input;
        private final InputStream output;
        private final ExecutorService reader;
        private final String fingerprint;
        private final ObjectMapper mapper;

        private WorkerProcess(Process process, String fingerprint, ObjectMapper mapper) {
            this.process = process;
            this.input = process.getOutputStream();
            this.output = process.getInputStream();
            this.fingerprint = fingerprint;
            this.mapper = mapper;
            this.reader = Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "web-fetch-worker-reader");
                thread.setDaemon(true);
                return thread;
            });
        }

        static WorkerProcess start(FetchIsolationSettings settings, ObjectMapper mapper) throws IOException {
            ProcessBuilder builder = new ProcessBuilder(command(settings));
            builder.redirectError(ProcessBuilder.Redirect.DISCARD);
            return new WorkerProcess(builder.start(), settings.fingerprint(), mapper);
        }

        synchronized FetchWorkerProtocol.Response exchange(String request, int maxOutputBytes,
                                                            long timeoutMillis) throws Exception {
            if (!alive()) throw new IllegalStateException("fetch worker exited before accepting a request");
            input.write(request.getBytes(StandardCharsets.UTF_8));
            input.write('\n');
            input.flush();
            Future<String> result = reader.submit(() -> readBoundedLine(output, maxOutputBytes));
            String line;
            try {
                line = result.get(Math.max(1, timeoutMillis), TimeUnit.MILLISECONDS);
            } catch (TimeoutException error) {
                result.cancel(true);
                throw new IllegalStateException("fetch worker exceeded the wall-clock limit", error);
            } catch (ExecutionException error) {
                Throwable cause = error.getCause();
                if (cause instanceof Exception exception) throw exception;
                throw new IllegalStateException("fetch worker response failed", cause);
            }
            if (line == null) throw new IllegalStateException("fetch worker exited without a response");
            if (!line.startsWith(FetchWorkerProtocol.RESPONSE_PREFIX)) {
                throw new IllegalStateException("fetch worker returned an invalid response");
            }
            String json = line.substring(FetchWorkerProtocol.RESPONSE_PREFIX.length());
            return mapper.readValue(json, FetchWorkerProtocol.Response.class);
        }

        boolean matches(FetchIsolationSettings settings) {
            return fingerprint.equals(settings.fingerprint());
        }

        boolean alive() {
            return process.isAlive();
        }

        @Override
        public void close() {
            try {
                input.close();
            } catch (IOException ignored) {
                // Process teardown below is authoritative.
            }
            process.destroy();
            try {
                if (!process.waitFor(500, TimeUnit.MILLISECONDS)) process.destroyForcibly();
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
            reader.shutdownNow();
        }

        private static List<String> command(FetchIsolationSettings settings) {
            String java = Path.of(System.getProperty("java.home"), "bin",
                    isWindows() ? "java.exe" : "java").toString();
            String classpath = System.getProperty("java.class.path");
            List<String> command = new ArrayList<>();
            command.add(java);
            command.add("-Xmx" + settings.memoryMb() + "m");
            command.add("-Xss512k");
            command.add("-XX:MaxMetaspaceSize=128m");
            command.add("-XX:ReservedCodeCacheSize=64m");
            command.add("-XX:ActiveProcessorCount=" + settings.activeProcessors());
            command.add("-XX:+ExitOnOutOfMemoryError");
            command.add("-Djava.awt.headless=true");
            if (singleBootJar(classpath)) {
                command.add("-Dloader.main=" + settings.mainClass());
                command.add("-cp");
                command.add(classpath);
                command.add("org.springframework.boot.loader.launch.PropertiesLauncher");
            } else {
                command.add("-cp");
                command.add(classpath);
                command.add(settings.mainClass());
            }
            return List.copyOf(command);
        }

        private static boolean singleBootJar(String classpath) {
            if (classpath == null || classpath.contains(File.pathSeparator)
                    || !classpath.toLowerCase(java.util.Locale.ROOT).endsWith(".jar")) return false;
            try (java.util.jar.JarFile jar = new java.util.jar.JarFile(classpath)) {
                return jar.getEntry("BOOT-INF/classes/") != null;
            } catch (IOException error) {
                return false;
            }
        }

        private static boolean isWindows() {
            return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
        }

        private static String readBoundedLine(InputStream input, int maximum) throws IOException {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            while (true) {
                int value = input.read();
                if (value < 0) return output.size() == 0 ? null : output.toString(StandardCharsets.UTF_8);
                if (value == '\n') return output.toString(StandardCharsets.UTF_8);
                if (value != '\r') output.write(value);
                if (output.size() > maximum) {
                    throw new IOException("fetch worker output exceeded size limit");
                }
            }
        }
    }
}
