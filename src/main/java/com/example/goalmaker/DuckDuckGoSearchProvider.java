package com.example.goalmaker;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class DuckDuckGoSearchProvider implements SearchProvider {
    private final String endpoint;
    private final WebHttpClient http;
    private final int maxAttempts;
    private final long retryDelayMillis;
    private final int maxResponseBytes;

    DuckDuckGoSearchProvider(String endpoint, WebHttpClient http, int maxAttempts,
                             long retryDelayMillis, int maxResponseBytes) {
        this.endpoint = endpoint;
        this.http = http;
        this.maxAttempts = maxAttempts;
        this.retryDelayMillis = retryDelayMillis;
        this.maxResponseBytes = maxResponseBytes;
    }

    @Override
    public String name() {
        return "duckduckgo";
    }

    @Override
    public List<SearchResult> search(SearchRequest request) throws Exception {
        StringBuilder url = new StringBuilder(endpoint)
                .append(endpoint.contains("?") ? "&" : "?")
                .append("q=").append(encode(request.query()));
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

        WebHttpClient.Response response = http.get(URI.create(url.toString()), "text/html",
                Duration.ofSeconds(20), maxAttempts, retryDelayMillis, maxResponseBytes);
        if (response.status() / 100 != 2) {
            throw new IllegalStateException("HTTP " + response.status() + " from DuckDuckGo");
        }
        if (response.truncated()) throw new IllegalStateException("DuckDuckGo response exceeded size limit");
        String lowerBody = response.body().toLowerCase(Locale.ROOT);
        if (lowerBody.contains("captcha") || lowerBody.contains("anomaly-modal")
                || lowerBody.contains("challenge-form")) {
            throw new IllegalStateException("DuckDuckGo blocked the automated search request");
        }

        Document document = Jsoup.parse(response.body(), url.toString());
        List<SearchResult> results = new ArrayList<>();
        for (Element result : document.select("div.result")) {
            Element link = result.selectFirst("a.result__a");
            if (link == null) continue;
            Element snippet = result.selectFirst(".result__snippet");
            Element timestamp = result.selectFirst(".result__timestamp");
            results.add(new SearchResult(
                    link.text().trim(),
                    decodeUrl(link.attr("href")),
                    snippet == null ? "" : snippet.text().trim(),
                    timestamp == null ? "" : timestamp.text().trim(),
                    name(),
                    name()));
            if (results.size() >= request.maxResults()) break;
        }
        return List.copyOf(results);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String decodeUrl(String href) {
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
