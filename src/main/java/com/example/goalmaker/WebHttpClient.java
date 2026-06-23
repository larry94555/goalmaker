package com.example.goalmaker;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Optional;

final class WebHttpClient {
    private static final long MAX_RETRY_DELAY_MILLIS = 5_000;

    private final HttpClient client;

    WebHttpClient(HttpClient client) {
        this.client = client;
    }

    Response get(URI uri, String accept, Duration timeout, int maxAttempts,
                 long retryDelayMillis, int maxBytes) throws Exception {
        BinaryResponse response = getBytes(uri, accept, timeout, maxAttempts, retryDelayMillis, maxBytes);
        return new Response(response.status(), response.headers(),
                new String(response.body(), charset(response.headers())), response.truncated());
    }

    BinaryResponse getBytes(URI uri, String accept, Duration timeout, int maxAttempts,
                            long retryDelayMillis, int maxBytes) throws Exception {
        Exception lastFailure = null;
        int attempts = Math.max(1, maxAttempts);
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                HttpRequest request = HttpRequest.newBuilder(uri)
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) goalmaker/0.2")
                        .header("Accept", accept)
                        .timeout(timeout)
                        .GET()
                        .build();
                HttpResponse<InputStream> response = client.send(
                        request, HttpResponse.BodyHandlers.ofInputStream());
                BinaryBody body;
                try (InputStream stream = response.body()) {
                    body = readBody(stream, maxBytes);
                }
                BinaryResponse result = new BinaryResponse(response.statusCode(), response.headers(),
                        body.bytes(), body.truncated());
                if (!retryable(response.statusCode()) || attempt == attempts) return result;
                sleep(retryDelay(response.headers(), retryDelayMillis, attempt));
            } catch (IOException error) {
                lastFailure = error;
                if (attempt == attempts) throw error;
                sleep(exponentialDelay(retryDelayMillis, attempt));
            }
        }
        throw lastFailure == null ? new IOException("web request failed") : lastFailure;
    }

    private static BinaryBody readBody(InputStream stream, int maxBytes) throws IOException {
        int limit = Math.max(1, maxBytes);
        byte[] bytes = stream.readNBytes(limit + 1);
        boolean truncated = bytes.length > limit;
        int length = Math.min(bytes.length, limit);
        return new BinaryBody(java.util.Arrays.copyOf(bytes, length), truncated);
    }

    static String decode(BinaryResponse response) {
        return new String(response.body(), charset(response.headers()));
    }

    private static Charset charset(HttpHeaders headers) {
        String contentType = headers.firstValue("Content-Type").orElse("");
        for (String part : contentType.split(";")) {
            String value = part.trim();
            if (value.toLowerCase(Locale.ROOT).startsWith("charset=")) {
                try {
                    return Charset.forName(value.substring("charset=".length()).replace("\"", "").trim());
                } catch (Exception ignored) {
                    return StandardCharsets.UTF_8;
                }
            }
        }
        return StandardCharsets.UTF_8;
    }

    private static boolean retryable(int status) {
        return status == 408 || status == 425 || status == 429 || status >= 500;
    }

    private static long retryDelay(HttpHeaders headers, long baseDelay, int attempt) {
        Optional<String> value = headers.firstValue("Retry-After");
        if (value.isPresent()) {
            try {
                return Math.min(MAX_RETRY_DELAY_MILLIS,
                        Math.max(0, Long.parseLong(value.get().trim()) * 1_000));
            } catch (NumberFormatException ignored) {
                try {
                    long millis = Duration.between(ZonedDateTime.now(),
                            ZonedDateTime.parse(value.get(), DateTimeFormatter.RFC_1123_DATE_TIME)).toMillis();
                    return Math.min(MAX_RETRY_DELAY_MILLIS, Math.max(0, millis));
                } catch (Exception ignoredAgain) {
                    // Fall through to exponential backoff.
                }
            }
        }
        return exponentialDelay(baseDelay, attempt);
    }

    private static long exponentialDelay(long baseDelay, int attempt) {
        long base = Math.max(0, baseDelay);
        long multiplier = 1L << Math.min(10, Math.max(0, attempt - 1));
        return Math.min(MAX_RETRY_DELAY_MILLIS, base * multiplier);
    }

    private static void sleep(long millis) throws InterruptedException {
        if (millis > 0) Thread.sleep(millis);
    }

    record Response(int status, HttpHeaders headers, String body, boolean truncated) {}

    record BinaryResponse(int status, HttpHeaders headers, byte[] body, boolean truncated) {}

    private record BinaryBody(byte[] bytes, boolean truncated) {}
}
