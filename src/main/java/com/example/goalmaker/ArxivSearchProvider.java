package com.example.goalmaker;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class ArxivSearchProvider implements RoutedSearchProvider {
    private static final Set<String> STOP_WORDS = Set.of(
            "about", "academic", "articles", "find", "latest", "papers", "recent", "research",
            "show", "studies", "study", "that", "the", "what", "with");

    private final String endpoint;
    private final WebHttpClient http;
    private final int maxAttempts;
    private final long retryDelayMillis;
    private final int maxResponseBytes;

    ArxivSearchProvider(String endpoint, WebHttpClient http, int maxAttempts,
                        long retryDelayMillis, int maxResponseBytes) {
        this.endpoint = endpoint;
        this.http = http;
        this.maxAttempts = maxAttempts;
        this.retryDelayMillis = retryDelayMillis;
        this.maxResponseBytes = maxResponseBytes;
    }

    @Override
    public SearchIntent intent() {
        return SearchIntent.SCHOLARLY;
    }

    @Override
    public String name() {
        return "arxiv";
    }

    @Override
    public List<SearchResult> search(SearchRequest request) throws Exception {
        String expression = searchExpression(request.query());
        String url = endpoint + (endpoint.contains("?") ? "&" : "?")
                + "search_query=" + encode(expression)
                + "&start=" + ((request.page() - 1) * request.maxResults())
                + "&max_results=" + request.maxResults()
                + "&sortBy=relevance&sortOrder=descending";
        WebHttpClient.Response response = http.get(URI.create(url), "application/atom+xml",
                Duration.ofSeconds(30), maxAttempts, retryDelayMillis, maxResponseBytes);
        requireSuccess(response);

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        Document document = factory.newDocumentBuilder().parse(
                new ByteArrayInputStream(response.body().getBytes(StandardCharsets.UTF_8)));
        NodeList entries = document.getElementsByTagNameNS("http://www.w3.org/2005/Atom", "entry");
        List<SearchResult> results = new ArrayList<>();
        for (int index = 0; index < entries.getLength() && results.size() < request.maxResults(); index++) {
            Element entry = (Element) entries.item(index);
            String target = alternateLink(entry);
            if (target.isBlank()) target = text(entry, "id").replace("http://", "https://");
            if (target.isBlank()) continue;
            results.add(new SearchResult(
                    normalize(text(entry, "title")),
                    target,
                    normalize(text(entry, "summary")),
                    normalize(text(entry, "published")),
                    name(),
                    "arxiv"));
        }
        return List.copyOf(results);
    }

    private static String searchExpression(String query) {
        Set<String> terms = new LinkedHashSet<>();
        for (String token : query.toLowerCase(Locale.ROOT).split("[^a-z0-9.-]+")) {
            if (token.length() >= 2 && !STOP_WORDS.contains(token)) terms.add(token);
            if (terms.size() >= 8) break;
        }
        if (terms.isEmpty()) return "all:\"" + query.replace("\"", "") + "\"";
        return terms.stream().map(term -> "all:" + term).reduce((left, right) -> left + " AND " + right)
                .orElse("all:" + query);
    }

    private static String alternateLink(Element entry) {
        NodeList links = entry.getElementsByTagNameNS("http://www.w3.org/2005/Atom", "link");
        for (int index = 0; index < links.getLength(); index++) {
            Element link = (Element) links.item(index);
            if ("alternate".equals(link.getAttribute("rel"))) return link.getAttribute("href");
        }
        return "";
    }

    private static String text(Element entry, String name) {
        NodeList nodes = entry.getElementsByTagNameNS("http://www.w3.org/2005/Atom", name);
        return nodes.getLength() == 0 ? "" : nodes.item(0).getTextContent();
    }

    private static String normalize(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }

    private static void requireSuccess(WebHttpClient.Response response) {
        if (response.status() / 100 != 2) {
            throw new IllegalStateException("HTTP " + response.status() + " from arXiv");
        }
        if (response.truncated()) throw new IllegalStateException("arXiv response exceeded size limit");
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
