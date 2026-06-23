package com.example.goalmaker;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Fallback general-web provider backed by the DuckDuckGo Lite front end. Its markup is a flat table
 * of {@code a.result-link} rows with matching {@code td.result-snippet} cells, which is far more
 * stable and less aggressively rate-limited than the rich HTML endpoint, so it keeps the token-free
 * search path working when SearXNG is absent and the HTML endpoint is blocked or empty.
 */
final class DuckDuckGoLiteSearchProvider implements SearchProvider {
    private final String endpoint;
    private final WebHttpClient http;
    private final int maxAttempts;
    private final long retryDelayMillis;
    private final int maxResponseBytes;

    DuckDuckGoLiteSearchProvider(String endpoint, WebHttpClient http, int maxAttempts,
                                 long retryDelayMillis, int maxResponseBytes) {
        this.endpoint = endpoint;
        this.http = http;
        this.maxAttempts = maxAttempts;
        this.retryDelayMillis = retryDelayMillis;
        this.maxResponseBytes = maxResponseBytes;
    }

    @Override
    public String name() {
        return "duckduckgo-lite";
    }

    @Override
    public List<SearchResult> search(SearchRequest request) throws Exception {
        StringBuilder url = new StringBuilder(endpoint)
                .append(endpoint.contains("?") ? "&" : "?")
                .append("q=").append(DuckDuckGo.encode(request.query()));
        DuckDuckGo.appendCommonParams(url, request);

        WebHttpClient.Response response = http.get(URI.create(url.toString()), "text/html",
                Duration.ofSeconds(20), maxAttempts, retryDelayMillis, maxResponseBytes);
        if (response.status() / 100 != 2) {
            throw new IllegalStateException("HTTP " + response.status() + " from DuckDuckGo Lite");
        }
        if (response.truncated()) throw new IllegalStateException("DuckDuckGo Lite response exceeded size limit");
        if (DuckDuckGo.blocked(response.body())) {
            throw new IllegalStateException("DuckDuckGo Lite blocked the automated search request");
        }

        Document document = Jsoup.parse(response.body(), url.toString());
        Elements links = document.select("a.result-link");
        Elements snippets = document.select("td.result-snippet");
        int limit = DuckDuckGo.candidateLimit(request.maxResults());
        List<SearchResult> results = new ArrayList<>();
        for (int index = 0; index < links.size(); index++) {
            Element link = links.get(index);
            String target = DuckDuckGo.decodeRedirect(link.attr("href"));
            if (target.isBlank()) continue;
            String snippet = index < snippets.size() ? snippets.get(index).text().trim() : "";
            results.add(new SearchResult(link.text().trim(), target, snippet, "", name(), name()));
            if (results.size() >= limit) break;
        }
        return List.copyOf(results);
    }
}
