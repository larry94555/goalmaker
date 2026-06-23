package com.example.goalmaker;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Shared helpers for the token-free DuckDuckGo search endpoints. The HTML and Lite front ends use
 * the same query parameters, the same {@code uddg=} redirect wrapping, and the same block markers,
 * so the resilient fallback chain keeps a single copy of that logic here.
 */
final class DuckDuckGo {
    private DuckDuckGo() {}

    static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /** Appends the region, recency, safe-search, and paging parameters common to both front ends. */
    static void appendCommonParams(StringBuilder url, SearchRequest request) {
        if (!"auto".equalsIgnoreCase(request.language())) {
            url.append("&kl=").append(encode(request.language()));
        }
        if (!request.timeRange().isBlank()) {
            url.append("&df=").append(switch (request.timeRange()) {
                case "day" -> "d";
                case "month" -> "m";
                case "year" -> "y";
                default -> "";
            });
        }
        url.append("&kp=").append(switch (request.safeSearch()) {
            case 0 -> "-2";
            case 2 -> "1";
            default -> "-1";
        });
        if (request.page() > 1) url.append("&s=").append((request.page() - 1) * 30);
    }

    /** True when the response is a captcha or anti-automation interstitial rather than results. */
    static boolean blocked(String body) {
        String lower = body.toLowerCase(Locale.ROOT);
        return lower.contains("captcha") || lower.contains("anomaly-modal")
                || lower.contains("challenge-form");
    }

    /**
     * Collect a larger candidate pool than is finally returned so the caller can re-rank by
     * relevance; a single result page already carries these rows at no extra request.
     */
    static int candidateLimit(int maxResults) {
        return Math.max(maxResults, Math.min(50, maxResults * 3));
    }

    /** Unwraps DuckDuckGo's {@code /l/?uddg=} redirect into the destination URL. */
    static String decodeRedirect(String href) {
        if (href == null) return "";
        int start = href.indexOf("uddg=");
        if (start < 0) return href;
        String encoded = href.substring(start + 5);
        int ampersand = encoded.indexOf('&');
        if (ampersand >= 0) encoded = encoded.substring(0, ampersand);
        try {
            return URLDecoder.decode(encoded, StandardCharsets.UTF_8);
        } catch (Exception error) {
            return href;
        }
    }
}
