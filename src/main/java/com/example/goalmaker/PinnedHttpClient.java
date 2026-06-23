package com.example.goalmaker;

import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.ResponseBody;

import java.io.IOException;
import java.io.InputStream;
import java.net.Proxy;
import java.net.URI;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

final class PinnedHttpClient implements FetchHttpClient {
    private static final long MAX_RETRY_DELAY_MILLIS = 5_000;

    private final OkHttpClient client;

    PinnedHttpClient(PinnedDns dns) {
        this.client = new OkHttpClient.Builder()
                .dns(dns)
                .proxy(Proxy.NO_PROXY)
                .followRedirects(false)
                .followSslRedirects(false)
                .retryOnConnectionFailure(false)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public FetchHttpClient.Response getBytes(URI uri, String accept, Duration timeout, int maxAttempts,
                                             long retryDelayMillis, int maxBytes,
                                             FetchBudget budget) throws Exception {
        Exception lastFailure = null;
        int attempts = Math.max(1, maxAttempts);
        for (int attempt = 1; attempt <= attempts; attempt++) {
            budget.consumeHttpRequest();
            try {
                Request request = new Request.Builder()
                        .url(uri.toString())
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) goalmaker/0.3")
                        .header("Accept", accept)
                        .get()
                        .build();
                Call call = client.newCall(request);
                call.timeout().timeout(budget.boundedMillis(timeout), TimeUnit.MILLISECONDS);
                try (okhttp3.Response response = call.execute()) {
                    BinaryBody body;
                    ResponseBody responseBody = response.body();
                    if (responseBody == null) {
                        body = new BinaryBody(new byte[0], false);
                    } else {
                        try (InputStream stream = responseBody.byteStream()) {
                            body = readBody(stream, maxBytes);
                        }
                    }
                    FetchHttpClient.Response result = new FetchHttpClient.Response(response.code(),
                            response.headers().toMultimap(), body.bytes(), body.truncated());
                    if (!retryable(response.code()) || attempt == attempts) return result;
                    budget.sleep(retryDelay(result, retryDelayMillis, attempt));
                }
            } catch (IOException error) {
                lastFailure = error;
                if (attempt == attempts) throw error;
                budget.sleep(backoff(retryDelayMillis, attempt));
            }
        }
        throw lastFailure == null ? new IOException("web request failed") : lastFailure;
    }

    private static BinaryBody readBody(InputStream stream, int maxBytes) throws IOException {
        int limit = Math.max(1, maxBytes);
        byte[] bytes = stream.readNBytes(limit + 1);
        boolean truncated = bytes.length > limit;
        return new BinaryBody(Arrays.copyOf(bytes, Math.min(bytes.length, limit)), truncated);
    }

    private static boolean retryable(int status) {
        return status == 408 || status == 425 || status == 429 || status >= 500;
    }

    private static long retryDelay(FetchHttpClient.Response response, long baseMillis, int attempt) {
        String value = response.firstHeader("Retry-After").orElse("").trim();
        if (!value.isBlank()) {
            try {
                return Math.min(MAX_RETRY_DELAY_MILLIS, Math.max(0, Long.parseLong(value) * 1_000));
            } catch (NumberFormatException ignored) {
                try {
                    long millis = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME)
                            .toInstant().toEpochMilli() - System.currentTimeMillis();
                    return Math.min(MAX_RETRY_DELAY_MILLIS, Math.max(0, millis));
                } catch (Exception ignoredAgain) {
                    // Fall through to bounded exponential backoff.
                }
            }
        }
        return backoff(baseMillis, attempt);
    }

    private static long backoff(long baseMillis, int attempt) {
        long base = Math.max(0, baseMillis);
        int shift = Math.min(5, Math.max(0, attempt - 1));
        return Math.min(MAX_RETRY_DELAY_MILLIS, base * (1L << shift));
    }

    private record BinaryBody(byte[] bytes, boolean truncated) {}
}
