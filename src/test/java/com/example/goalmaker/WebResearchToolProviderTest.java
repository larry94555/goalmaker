package com.example.goalmaker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebResearchToolProviderTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void gathersDiverseEvidenceAndReportsSufficientSources() throws Exception {
        FakeSearch search = new FakeSearch(mapper, searchPayload(List.of(
                result(1, "Official release", "https://updates.example.gov/release", "2026-06-22"),
                result(2, "Additional release notes", "https://updates.example.gov/details", ""),
                result(3, "University analysis", "https://research.example.edu/analysis", "2026-06-21"),
                result(4, "Unavailable source", "https://broken.example.org/article", ""),
                result(5, "Independent review", "https://independent.example.net/review", ""))));
        FakeFetch fetch = new FakeFetch(mapper);
        fetch.add("https://updates.example.gov/release", "Official release",
                "Spring Boot 4.0 changes its baseline and improves startup diagnostics. Other material follows.");
        fetch.add("https://updates.example.gov/details", "Details",
                "Spring Boot 4.0 includes migration notes and compatibility guidance.");
        fetch.add("https://research.example.edu/analysis", "University analysis",
                "Researchers found that Spring Boot 4.0 improves startup diagnostics and observability.");
        fetch.fail("https://broken.example.org/article", "HTTP 503 when fetching source");
        fetch.add("https://independent.example.net/review", "Independent review",
                "The review explains Spring Boot 4.0 compatibility changes for application developers.");
        fetch.requireConcurrency();
        WebResearchToolProvider research = new WebResearchToolProvider(mapper, search, fetch);
        ReflectionTestUtils.setField(research, "maxConcurrency", 4);
        ToolCatalog catalog = catalog(search, fetch, research);

        JsonNode payload = payload(catalog.execute("web_research", Map.of(
                "query", "What changed in Spring Boot 4.0?",
                "max_sources", 3,
                "min_sources", 2)));

        assertEquals("sufficient_sources", payload.path("corroboration").path("status").asText());
        assertTrue(payload.path("corroboration").path("source_threshold_met").asBoolean());
        assertEquals(3, payload.path("evidence").size());
        Set<String> domains = ConcurrentHashMap.newKeySet();
        payload.path("evidence").forEach(item -> domains.add(item.path("domain").asText()));
        assertEquals(3, domains.size());
        assertTrue(payload.path("evidence").toString().contains("Spring Boot 4.0"));
        assertTrue(payload.path("evidence").path(0).has("evidence_score"));
        assertEquals(1, payload.path("fetch_failures").size());
        assertTrue(payload.path("corroboration").path("assessment").asText()
                .contains("semantic agreement still requires review"));
        assertEquals(5, fetch.calls.size());
        assertTrue(fetch.maxActive.get() >= 2);
    }

    @Test
    void reportsPartialEvidenceWhenIndependentSourceThresholdIsNotMet() throws Exception {
        FakeSearch search = new FakeSearch(mapper, searchPayload(List.of(
                result(1, "Only source", "https://single.example.com/article", ""))));
        FakeFetch fetch = new FakeFetch(mapper);
        fetch.add("https://single.example.com/article", "Only source",
                "The only available source contains information about the requested subject.");
        WebResearchToolProvider research = new WebResearchToolProvider(mapper, search, fetch);

        JsonNode payload = payload(catalog(search, fetch, research).execute("web_research", Map.of(
                "query", "What happened?", "max_sources", 3, "min_sources", 2)));

        assertEquals("partial_sources", payload.path("corroboration").path("status").asText());
        assertFalse(payload.path("corroboration").path("source_threshold_met").asBoolean());
        assertEquals(1, payload.path("evidence").size());
        assertEquals("The independent-source threshold was not met.",
                payload.path("corroboration").path("assessment").asText());
    }

    @Test
    void validatesResearchSourceThresholds() {
        FakeSearch search = new FakeSearch(mapper, searchPayload(List.of()));
        FakeFetch fetch = new FakeFetch(mapper);
        WebResearchToolProvider research = new WebResearchToolProvider(mapper, search, fetch);

        String result = catalog(search, fetch, research).execute("web_research", Map.of(
                "query", "test", "max_sources", 2, "min_sources", 3));

        assertEquals("ERROR: min_sources must not exceed max_sources", result);
    }

    @Test
    void preservesFetchedDocumentProvenanceInEvidence() throws Exception {
        String url = "https://papers.example.org/document.pdf";
        FakeSearch search = new FakeSearch(mapper, searchPayload(List.of(
                result(1, "Search title", url, "2025-01-01"))));
        FakeFetch fetch = new FakeFetch(mapper);
        Map<String, Object> fetched = new LinkedHashMap<>();
        fetched.put("url", url);
        fetched.put("canonical_url", "https://papers.example.org/canonical.pdf");
        fetched.put("title", "Document title");
        fetched.put("author", "PDF Author");
        fetched.put("published_at", "2026-06-20T10:15:30Z");
        fetched.put("modified_at", "2026-06-21T00:00:00Z");
        fetched.put("content_type", "application/pdf");
        fetched.put("extraction_method", "pdfbox");
        fetched.put("page_count", 12);
        fetched.put("pages_extracted", 12);
        fetched.put("metadata_conflicts", List.of(Map.of("field", "title")));
        fetched.put("fetch_policy", Map.of("dns_pinned", true, "allowed_ports", List.of(80, 443)));
        fetched.put("fetch_isolation", Map.of("mode", "worker-process", "enabled", true));
        fetched.put("retrieved_at", "2026-06-22T00:00:00Z");
        fetched.put("truncated", false);
        fetched.put("content", "The document contains evidence relevant to the research question.");
        fetch.add(url, fetched);
        WebResearchToolProvider research = new WebResearchToolProvider(mapper, search, fetch);

        JsonNode payload = payload(catalog(search, fetch, research).execute("web_research", Map.of(
                "query", "What evidence is in the document?", "max_sources", 1, "min_sources", 1)));
        JsonNode evidence = payload.path("evidence").path(0);

        assertEquals("https://papers.example.org/canonical.pdf", evidence.path("canonical_url").asText());
        assertEquals("PDF Author", evidence.path("author").asText());
        assertEquals("2026-06-20T10:15:30Z", evidence.path("published_at").asText());
        assertEquals("application/pdf", evidence.path("content_type").asText());
        assertEquals("pdfbox", evidence.path("extraction_method").asText());
        assertEquals(12, evidence.path("page_count").asInt());
        assertEquals(1, evidence.path("metadata_conflicts").size());
        assertTrue(evidence.path("fetch_policy").path("dns_pinned").asBoolean());
        assertEquals("worker-process", evidence.path("fetch_isolation").path("mode").asText());
    }

    private ToolCatalog catalog(WebSearchToolProvider search, WebFetchToolProvider fetch,
                                WebResearchToolProvider research) {
        ToolCatalog catalog = new ToolCatalog(
                new SkillToolProvider(mapper), new McpToolProvider(mapper), search, fetch, research);
        catalog.refresh();
        return catalog;
    }

    private JsonNode payload(String wrapped) throws Exception {
        int start = wrapped.indexOf('\n') + 1;
        int end = wrapped.lastIndexOf("\n[END UNTRUSTED CONTENT]");
        return mapper.readTree(wrapped.substring(start, end));
    }

    private static Map<String, Object> searchPayload(List<Map<String, Object>> results) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("provider", "test-search");
        payload.put("cached", false);
        payload.put("result_count", results.size());
        payload.put("results", results);
        return payload;
    }

    private static Map<String, Object> result(int rank, String title, String url, String publishedAt) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rank", rank);
        result.put("title", title);
        result.put("url", url);
        result.put("snippet", "Search snippet");
        result.put("provider", "searxng");
        result.put("engine", "test-engine");
        if (!publishedAt.isBlank()) result.put("published_at", publishedAt);
        return result;
    }

    private static final class FakeSearch extends WebSearchToolProvider {
        private final Map<String, Object> payload;

        FakeSearch(ObjectMapper mapper, Map<String, Object> payload) {
            super(mapper);
            this.payload = payload;
        }

        @Override
        Map<String, Object> searchPayload(Map<String, Object> arguments) {
            return payload;
        }
    }

    private static final class FakeFetch extends WebFetchToolProvider {
        private final Map<String, Map<String, Object>> payloads = new ConcurrentHashMap<>();
        private final Map<String, String> failures = new ConcurrentHashMap<>();
        private final List<String> calls = java.util.Collections.synchronizedList(new ArrayList<>());
        private final AtomicInteger active = new AtomicInteger();
        private final AtomicInteger maxActive = new AtomicInteger();
        private volatile CountDownLatch concurrencyGate;

        FakeFetch(ObjectMapper mapper) {
            super(mapper);
        }

        void add(String url, String title, String content) {
            payloads.put(url, Map.of(
                    "url", url,
                    "title", title,
                    "retrieved_at", "2026-06-22T00:00:00Z",
                    "truncated", false,
                    "content", content));
        }

        void add(String url, Map<String, Object> payload) {
            payloads.put(url, Map.copyOf(payload));
        }

        void fail(String url, String message) {
            failures.put(url, message);
        }

        void requireConcurrency() {
            concurrencyGate = new CountDownLatch(2);
        }

        @Override
        Map<String, Object> fetchPayload(Map<String, Object> arguments) {
            String url = String.valueOf(arguments.get("url"));
            calls.add(url);
            int current = active.incrementAndGet();
            maxActive.accumulateAndGet(current, Math::max);
            try {
                CountDownLatch gate = concurrencyGate;
                if (gate != null) {
                    gate.countDown();
                    if (!gate.await(2, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("research fetches did not overlap");
                    }
                }
                if (failures.containsKey(url)) throw new IllegalStateException(failures.get(url));
                Map<String, Object> payload = payloads.get(url);
                if (payload == null) throw new IllegalStateException("missing fake page");
                return payload;
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("fake fetch interrupted", error);
            } finally {
                active.decrementAndGet();
            }
        }
    }
}
