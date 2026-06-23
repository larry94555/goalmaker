package com.example.goalmaker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

final class HtmlDocumentExtractor {
    private static final String NOISE = "script, style, noscript, svg, nav, footer, header, form, aside, "
            + "iframe, canvas, template, [hidden], [aria-hidden=true], .advertisement, .advert, .cookie, "
            + ".newsletter, .social-share, .related-content";
    private static final Pattern NEGATIVE = Pattern.compile(
            "(?i)(comment|footer|header|menu|nav|sidebar|share|social|sponsor|promo|advert|cookie|related)");

    private final ObjectMapper mapper;

    HtmlDocumentExtractor(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    Extraction extract(String html, URI source) {
        Document document = Jsoup.parse(html, source.toString());
        Metadata metadata = metadata(document, source);
        document.select(NOISE).remove();
        Element root = readabilityRoot(document);
        String text = readableText(root);
        String method = root == null || root == document.body()
                ? "html-body-fallback" : "html-readability";
        return new Extraction(text, metadata.title(), metadata.canonicalUrl(), metadata.author(),
                metadata.publishedAt(), metadata.modifiedAt(), metadata.language(),
                method, metadata.conflicts());
    }

    private Metadata metadata(Document document, URI source) {
        MetadataCandidates candidates = new MetadataCandidates();
        addMeta(candidates, "title", document, "meta[property=og:title]", "content");
        addMeta(candidates, "title", document, "meta[name=twitter:title]", "content");
        candidates.add("title", document.title());
        Element heading = document.selectFirst("h1");
        if (heading != null) candidates.add("title", heading.text());

        addMeta(candidates, "author", document, "meta[name=author]", "content");
        addMeta(candidates, "author", document, "meta[property=article:author]", "content");
        Element author = document.selectFirst("[rel=author], [itemprop=author], .byline");
        if (author != null) candidates.add("author", author.hasAttr("content")
                ? author.attr("content") : author.text());

        addMeta(candidates, "published_at", document, "meta[property=article:published_time]", "content");
        addMeta(candidates, "published_at", document, "meta[name=date]", "content");
        addMetaValue(candidates, "published_at", document, "[itemprop=datePublished]");
        Element published = document.selectFirst("time[datetime]");
        if (published != null) candidates.add("published_at", published.attr("datetime"));

        addMeta(candidates, "modified_at", document, "meta[property=article:modified_time]", "content");
        addMetaValue(candidates, "modified_at", document, "[itemprop=dateModified]");

        Element canonical = document.selectFirst("link[rel=canonical][href]");
        if (canonical != null) candidates.add("canonical_url", absoluteHttpUrl(source, canonical.attr("href")));
        addMetaUrl(candidates, document, source, "meta[property=og:url]", "content");

        addMeta(candidates, "language", document, "html[lang]", "lang");
        addMeta(candidates, "language", document, "meta[http-equiv=content-language]", "content");
        addJsonLd(document, candidates, source);

        return new Metadata(
                candidates.first("title"),
                candidates.first("canonical_url"),
                candidates.first("author"),
                normalizeDate(candidates.first("published_at")),
                normalizeDate(candidates.first("modified_at")),
                candidates.first("language"),
                candidates.conflicts());
    }

    private void addJsonLd(Document document, MetadataCandidates candidates, URI source) {
        int count = 0;
        for (Element script : document.select("script[type=application/ld+json]")) {
            if (count++ >= 10 || script.data().length() > 100_000) continue;
            try {
                collectJsonLd(mapper.readTree(script.data()), candidates, source, 0);
            } catch (Exception ignored) {
                // Invalid JSON-LD must not prevent page extraction.
            }
        }
    }

    private void collectJsonLd(JsonNode node, MetadataCandidates candidates, URI source, int depth) {
        if (node == null || depth > 4) return;
        if (node.isArray()) {
            for (JsonNode item : node) collectJsonLd(item, candidates, source, depth + 1);
            return;
        }
        if (!node.isObject()) return;
        candidates.add("title", text(node, "headline"));
        candidates.add("title", text(node, "name"));
        candidates.add("author", author(node.path("author")));
        candidates.add("published_at", text(node, "datePublished"));
        candidates.add("modified_at", text(node, "dateModified"));
        candidates.add("canonical_url", absoluteHttpUrl(source, text(node, "url")));
        collectJsonLd(node.path("@graph"), candidates, source, depth + 1);
        collectJsonLd(node.path("mainEntity"), candidates, source, depth + 1);
    }

    private static String author(JsonNode node) {
        if (node.isTextual()) return node.asText();
        if (node.isArray()) {
            List<String> authors = new ArrayList<>();
            for (JsonNode item : node) {
                String value = author(item);
                if (!value.isBlank()) authors.add(value);
            }
            return String.join(", ", authors);
        }
        return node.isObject() ? text(node, "name") : "";
    }

    private static Element readabilityRoot(Document document) {
        Element best = null;
        int bestScore = Integer.MIN_VALUE;
        int considered = 0;
        for (Element candidate : document.select("article, main, [role=main], section, div")) {
            if (considered++ >= 2_000) break;
            String text = candidate.text();
            boolean semanticRoot = candidate.tagName().equals("article")
                    || candidate.tagName().equals("main")
                    || "main".equalsIgnoreCase(candidate.attr("role"));
            if (text.length() < (semanticRoot ? 20 : 80)) continue;
            int score = Math.min(text.length(), 20_000)
                    + candidate.select("p").size() * 120
                    + commas(text) * 10;
            if (candidate.tagName().equals("article")) score += 800;
            if (candidate.tagName().equals("main")) score += 600;
            if ("main".equalsIgnoreCase(candidate.attr("role"))) score += 500;
            String signature = candidate.id() + " " + candidate.className();
            if (NEGATIVE.matcher(signature).find()) score -= 2_000;
            if (score > bestScore) {
                best = candidate;
                bestScore = score;
            }
        }
        return best == null ? document.body() : best;
    }

    private static String readableText(Element root) {
        if (root == null) return "";
        StringBuilder text = new StringBuilder();
        for (Element block : root.select("h1, h2, h3, p, li, blockquote, pre")) {
            String value = normalize(block.text());
            if (value.isBlank()) continue;
            if (text.length() > 0) text.append(' ');
            text.append(value);
        }
        return text.isEmpty() ? normalize(root.text()) : normalize(text.toString());
    }

    private static int commas(String value) {
        int count = 0;
        for (int index = 0; index < value.length(); index++) if (value.charAt(index) == ',') count++;
        return count;
    }

    private static void addMeta(MetadataCandidates candidates, String field, Document document,
                                String selector, String attribute) {
        for (Element element : document.select(selector)) candidates.add(field, element.attr(attribute));
    }

    private static void addMetaValue(MetadataCandidates candidates, String field, Document document,
                                     String selector) {
        for (Element element : document.select(selector)) {
            String value = element.hasAttr("content") ? element.attr("content")
                    : element.hasAttr("datetime") ? element.attr("datetime") : element.text();
            candidates.add(field, value);
        }
    }

    private static void addMetaUrl(MetadataCandidates candidates, Document document, URI source,
                                   String selector, String attribute) {
        for (Element element : document.select(selector)) {
            candidates.add("canonical_url", absoluteHttpUrl(source, element.attr(attribute)));
        }
    }

    private static String absoluteHttpUrl(URI source, String value) {
        if (value == null || value.isBlank()) return "";
        try {
            URI resolved = source.resolve(value.trim()).normalize();
            String scheme = resolved.getScheme() == null ? "" : resolved.getScheme().toLowerCase(Locale.ROOT);
            if (!(scheme.equals("http") || scheme.equals("https")) || resolved.getHost() == null
                    || resolved.getUserInfo() != null) return "";
            return resolved.toString();
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String normalizeDate(String value) {
        if (value == null || value.isBlank()) return "";
        String candidate = value.trim();
        try {
            return Instant.parse(candidate).toString();
        } catch (Exception ignored) {
            try {
                return OffsetDateTime.parse(candidate).toInstant().toString();
            } catch (Exception ignoredAgain) {
                try {
                    return ZonedDateTime.parse(candidate, DateTimeFormatter.RFC_1123_DATE_TIME)
                            .toInstant().toString();
                } catch (Exception ignoredThird) {
                    try {
                        return LocalDate.parse(candidate).toString();
                    } catch (Exception ignoredFourth) {
                        return candidate;
                    }
                }
            }
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isValueNode() ? value.asText("").trim() : "";
    }

    private static String normalize(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }

    record Extraction(String text, String title, String canonicalUrl, String author,
                      String publishedAt, String modifiedAt, String language,
                      String method, List<Map<String, Object>> metadataConflicts) {}

    private record Metadata(String title, String canonicalUrl, String author,
                            String publishedAt, String modifiedAt, String language,
                            List<Map<String, Object>> conflicts) {}

    private static final class MetadataCandidates {
        private final Map<String, Set<String>> values = new LinkedHashMap<>();

        void add(String field, String value) {
            String normalized = normalize(value);
            if (!normalized.isBlank()) {
                values.computeIfAbsent(field, ignored -> new LinkedHashSet<>()).add(normalized);
            }
        }

        String first(String field) {
            Set<String> candidates = values.get(field);
            return candidates == null || candidates.isEmpty() ? "" : candidates.iterator().next();
        }

        List<Map<String, Object>> conflicts() {
            List<Map<String, Object>> conflicts = new ArrayList<>();
            for (Map.Entry<String, Set<String>> entry : values.entrySet()) {
                if (entry.getValue().size() < 2) continue;
                List<String> candidates = List.copyOf(entry.getValue());
                conflicts.add(Map.of(
                        "field", entry.getKey(),
                        "selected", candidates.get(0),
                        "alternatives", candidates.subList(1, candidates.size())));
            }
            return List.copyOf(conflicts);
        }
    }
}
