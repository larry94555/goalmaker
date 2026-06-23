package com.example.goalmaker;

import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

interface FetchHttpClient {
    Pattern CHARSET = Pattern.compile("(?i)charset\\s*=\\s*[\\\"']?([^;\\s\\\"']+)");

    Response getBytes(URI uri, String accept, Duration timeout, int maxAttempts,
                      long retryDelayMillis, int maxBytes, FetchBudget budget) throws Exception;

    record Response(int status, Map<String, List<String>> headers, byte[] body, boolean truncated) {
        Optional<String> firstHeader(String name) {
            for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
                if (entry.getKey().equalsIgnoreCase(name) && !entry.getValue().isEmpty()) {
                    return Optional.ofNullable(entry.getValue().get(0));
                }
            }
            return Optional.empty();
        }

        String decode() {
            return new String(body, charset());
        }

        private Charset charset() {
            Matcher matcher = CHARSET.matcher(firstHeader("Content-Type").orElse(""));
            if (matcher.find()) {
                try {
                    return Charset.forName(matcher.group(1).trim());
                } catch (Exception ignored) {
                    // Invalid remote charset labels fall back to UTF-8.
                }
            }
            return StandardCharsets.UTF_8;
        }
    }
}
