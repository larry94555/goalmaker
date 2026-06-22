package com.example.goalmaker;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class WebSearchToolProvider {
    @Value("${web.search.endpoint:https://html.duckduckgo.com/html/}")
    private String endpoint = "https://html.duckduckgo.com/html/";

    private final HttpClient http;

    public WebSearchToolProvider() {
        this(HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(10))
                .build());
    }

    WebSearchToolProvider(HttpClient http) {
        this.http = http;
    }

    public List<ToolDefinition> tools() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("query", Map.of("type", "string", "description", "Search query."));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("query"));
        return List.of(new ToolDefinition(
                "web_search",
                "Search the web (DuckDuckGo) and return the top results with titles, URLs, and snippets.",
                schema,
                "builtin:web_search",
                true,
                this::search));
    }

    private String search(Map<String, Object> arguments) {
        try {
            Object value = arguments.get("query");
            String query = value == null ? "" : String.valueOf(value);
            String url = endpoint + "?q=" + URLEncoder.encode(query, StandardCharsets.UTF_8);
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) goalmaker/0.1")
                    .header("Accept", "text/html")
                    .timeout(Duration.ofSeconds(20))
                    .GET()
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                return "ERROR: HTTP " + response.statusCode() + " from the search endpoint.";
            }

            Document document = Jsoup.parse(response.body(), url);
            List<String> results = new ArrayList<>();
            int rank = 1;
            for (Element result : document.select("div.result")) {
                Element link = result.selectFirst("a.result__a");
                if (link == null) continue;
                String title = link.text().trim();
                String href = decodeDuckDuckGoUrl(link.attr("href"));
                Element snippetElement = result.selectFirst(".result__snippet");
                String snippet = snippetElement == null ? "" : snippetElement.text().trim();
                results.add(rank + ". " + title + "\n   " + href
                        + (snippet.isBlank() ? "" : "\n   " + snippet));
                if (rank++ >= 6) break;
            }
            return results.isEmpty() ? "(no results)" : String.join("\n\n", results);
        } catch (Exception error) {
            return "ERROR: " + error.getMessage();
        }
    }

    private static String decodeDuckDuckGoUrl(String href) {
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
