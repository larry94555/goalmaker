package com.example.goalmaker;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

@Component
public class WebResearchToolProvider {
    private static final Set<String> STOP_WORDS = Set.of(
            "about", "after", "before", "from", "have", "into", "latest", "most", "that", "their",
            "then", "there", "these", "they", "this", "what", "when", "where", "which", "with", "would");

    @Value("${web.research.default-max-sources:3}") private int defaultMaxSources = 3;
    @Value("${web.research.default-min-sources:2}") private int defaultMinSources = 2;
    @Value("${web.research.candidate-multiplier:2}") private int candidateMultiplier = 2;
    @Value("${web.research.max-concurrency:4}") private int maxConcurrency = 4;
    @Value("${web.research.fetch-max-chars:12000}") private int fetchMaxChars = 12_000;
    @Value("${web.research.timeout-seconds:90}") private int timeoutSeconds = 90;

    private final ObjectMapper mapper;
    private final WebSearchToolProvider search;
    private final WebFetchToolProvider fetch;

    @Autowired
    public WebResearchToolProvider(ObjectMapper mapper, WebSearchToolProvider search,
                                   WebFetchToolProvider fetch) {
        this.mapper = mapper;
        this.search = search;
        this.fetch = fetch;
    }

    public List<ToolDefinition> tools() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("query", Map.of("type", "string", "description", "Research question or search query."));
        properties.put("max_sources", integerProperty("Maximum successfully fetched sources (1-5).", 1, 5));
        properties.put("min_sources", integerProperty("Independent sources required for sufficient evidence (1-5).", 1, 5));
        properties.put("language", Map.of("type", "string", "description", "Language or region code, or auto."));
        properties.put("time_range", Map.of("type", "string", "description", "Optional recency filter.",
                "enum", List.of("day", "month", "year")));
        properties.put("safe_search", integerProperty("Safe search: 0 off, 1 moderate, 2 strict.", 0, 2));
        properties.put("categories", Map.of("type", "array",
                "description", "Optional SearXNG categories such as general, news, science, or it.",
                "items", Map.of("type", "string")));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("query"));
        return List.of(new ToolDefinition(
                "web_research",
                "Search and fetch diverse web sources, then return citation-ready evidence and source sufficiency status.",
                schema,
                "builtin:web_research",
                true,
                this::research));
    }

    private String research(Map<String, Object> arguments) throws Exception {
        ResearchRequest request = ResearchRequest.from(arguments, defaultMaxSources, defaultMinSources);
        return mapper.writeValueAsString(researchPayload(request));
    }

    Map<String, Object> researchPayload(ResearchRequest request) {
        int candidateCount = Math.min(20, Math.max(request.maxSources(),
                request.maxSources() * Math.max(1, candidateMultiplier)));
        Map<String, Object> searchArguments = new LinkedHashMap<>();
        searchArguments.put("query", request.query());
        searchArguments.put("max_results", candidateCount);
        searchArguments.put("language", request.language());
        searchArguments.put("safe_search", request.safeSearch());
        if (!request.timeRange().isBlank()) searchArguments.put("time_range", request.timeRange());
        if (!request.categories().isEmpty()) searchArguments.put("categories", request.categories());

        Map<String, Object> searchPayload = search.searchPayload(searchArguments);
        List<Candidate> candidates = selectCandidates(searchResults(searchPayload), candidateCount);
        List<FetchOutcome> outcomes = fetchCandidates(candidates, request);
        List<Map<String, Object>> fetchedEvidence = outcomes.stream()
                .filter(outcome -> outcome.evidence() != null)
                .map(FetchOutcome::evidence)
                .sorted(Comparator.comparingInt(item -> -integer(item.get("evidence_score"), 0)))
                .toList();
        List<Map<String, Object>> evidence = selectEvidence(fetchedEvidence, request.maxSources());
        List<Map<String, Object>> failures = outcomes.stream()
                .filter(outcome -> outcome.error() != null)
                .map(outcome -> Map.<String, Object>of(
                        "url", outcome.candidate().url(),
                        "domain", outcome.candidate().domain(),
                        "error", outcome.error()))
                .toList();

        Set<String> domains = new LinkedHashSet<>();
        for (Map<String, Object> item : evidence) domains.add(string(item.get("domain")));
        boolean sufficient = evidence.size() >= request.minSources() && domains.size() >= request.minSources();
        String status = sufficient ? "sufficient_sources"
                : evidence.isEmpty() ? "insufficient_sources" : "partial_sources";

        Map<String, Object> corroboration = new LinkedHashMap<>();
        corroboration.put("status", status);
        corroboration.put("required_sources", request.minSources());
        corroboration.put("evidence_sources", evidence.size());
        corroboration.put("independent_domains", domains.size());
        corroboration.put("source_threshold_met", sufficient);
        corroboration.put("conflict_review_required", evidence.size() > 1);
        corroboration.put("assessment", sufficient
                ? "The independent-source threshold was met; semantic agreement still requires review."
                : "The independent-source threshold was not met.");

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("query", request.query());
        payload.put("generated_at", Instant.now().toString());
        payload.put("search_provider", searchPayload.getOrDefault("provider", "none"));
        payload.put("query_intents", searchPayload.getOrDefault("query_intents", List.of("general")));
        payload.put("search_providers_attempted",
                searchPayload.getOrDefault("providers_attempted", List.of()));
        if (searchPayload.containsKey("specialized_providers_cached")) {
            payload.put("specialized_providers_cached", searchPayload.get("specialized_providers_cached"));
        }
        payload.put("search_cached", searchPayload.getOrDefault("cached", false));
        payload.put("search_result_count", searchPayload.getOrDefault("result_count", 0));
        if (searchPayload.containsKey("provider_notes")) {
            payload.put("search_provider_notes", searchPayload.get("provider_notes"));
        }
        payload.put("corroboration", corroboration);
        payload.put("evidence", evidence);
        if (!failures.isEmpty()) payload.put("fetch_failures", failures);
        return payload;
    }

    private static List<Map<String, Object>> selectEvidence(List<Map<String, Object>> ranked, int maximum) {
        List<Map<String, Object>> selected = new ArrayList<>();
        Set<String> domains = new HashSet<>();
        for (Map<String, Object> item : ranked) {
            if (domains.add(string(item.get("domain")))) selected.add(item);
            if (selected.size() >= maximum) return List.copyOf(selected);
        }
        for (Map<String, Object> item : ranked) {
            if (!selected.contains(item)) selected.add(item);
            if (selected.size() >= maximum) break;
        }
        return List.copyOf(selected);
    }

    private List<FetchOutcome> fetchCandidates(List<Candidate> candidates, ResearchRequest request) {
        if (candidates.isEmpty()) return List.of();
        int threads = Math.max(1, Math.min(Math.max(1, maxConcurrency), candidates.size()));
        ExecutorService executor = Executors.newFixedThreadPool(threads, runnable -> {
            Thread thread = new Thread(runnable, "web-research-fetch");
            thread.setDaemon(true);
            return thread;
        });
        List<Map.Entry<Candidate, Future<FetchOutcome>>> futures = new ArrayList<>();
        for (Candidate candidate : candidates) {
            futures.add(Map.entry(candidate, executor.submit(() -> fetchCandidate(candidate, request.query()))));
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(Math.max(1, timeoutSeconds));
        List<FetchOutcome> outcomes = new ArrayList<>();
        try {
            for (Map.Entry<Candidate, Future<FetchOutcome>> entry : futures) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    entry.getValue().cancel(true);
                    outcomes.add(new FetchOutcome(entry.getKey(), null, "research fetch deadline exceeded"));
                    continue;
                }
                try {
                    outcomes.add(entry.getValue().get(remaining, TimeUnit.NANOSECONDS));
                } catch (Exception error) {
                    entry.getValue().cancel(true);
                    outcomes.add(new FetchOutcome(entry.getKey(), null, usefulMessage(error)));
                }
            }
        } finally {
            executor.shutdownNow();
        }
        return List.copyOf(outcomes);
    }

    private FetchOutcome fetchCandidate(Candidate candidate, String query) {
        try {
            Map<String, Object> fetched = fetch.fetchPayload(Map.of(
                    "url", candidate.url(), "max_chars", fetchMaxChars));
            String content = string(fetched.get("content"));
            Excerpt excerpt = excerpt(content, query);
            List<String> qualitySignals = qualitySignals(candidate);
            int evidenceScore = candidate.sourceScore() + excerpt.relevanceScore();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("title", string(fetched.get("title")).isBlank()
                    ? candidate.title() : string(fetched.get("title")));
            item.put("url", string(fetched.getOrDefault("url", candidate.url())));
            item.put("domain", candidate.domain());
            item.put("search_rank", candidate.rank());
            item.put("search_provider", candidate.provider());
            if (!candidate.engine().isBlank()) item.put("search_engine", candidate.engine());
            if (!candidate.publishedAt().isBlank()) item.put("published_at", candidate.publishedAt());
            item.put("retrieved_at", fetched.get("retrieved_at"));
            item.put("excerpt", excerpt.text());
            item.put("relevance_score", excerpt.relevanceScore());
            item.put("source_quality_signals", qualitySignals);
            item.put("evidence_score", evidenceScore);
            item.put("content_truncated", fetched.getOrDefault("truncated", false));
            return new FetchOutcome(candidate, item, null);
        } catch (Exception error) {
            return new FetchOutcome(candidate, null, usefulMessage(error));
        }
    }

    private static List<Candidate> selectCandidates(List<Map<String, Object>> results, int maximum) {
        List<Candidate> ranked = new ArrayList<>();
        for (Map<String, Object> item : results) {
            String url = string(item.get("url"));
            String domain = domain(url);
            if (url.isBlank() || domain.isBlank()) continue;
            int rank = integer(item.get("rank"), ranked.size() + 1);
            Candidate candidate = new Candidate(
                    rank,
                    string(item.get("title")),
                    url,
                    domain,
                    string(item.get("published_at")),
                    string(item.get("provider")),
                    string(item.get("engine")),
                    sourceScore(rank, url, domain, string(item.get("title")), string(item.get("published_at"))));
            ranked.add(candidate);
        }
        ranked.sort(Comparator.comparingInt(Candidate::sourceScore).reversed()
                .thenComparingInt(Candidate::rank));

        List<Candidate> selected = new ArrayList<>();
        Set<String> domains = new HashSet<>();
        for (Candidate candidate : ranked) {
            if (domains.add(candidate.domain())) selected.add(candidate);
            if (selected.size() >= maximum) return List.copyOf(selected);
        }
        for (Candidate candidate : ranked) {
            if (!selected.contains(candidate)) selected.add(candidate);
            if (selected.size() >= maximum) break;
        }
        return List.copyOf(selected);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> searchResults(Map<String, Object> payload) {
        Object value = payload.get("results");
        if (!(value instanceof List<?> list)) return List.of();
        List<Map<String, Object>> results = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) results.add((Map<String, Object>) map);
        }
        return List.copyOf(results);
    }

    private static Excerpt excerpt(String content, String query) {
        if (content.isBlank()) return new Excerpt("", 0);
        Set<String> terms = queryTerms(query);
        String[] sentences = content.split("(?<=[.!?])\\s+");
        List<Sentence> ranked = new ArrayList<>();
        for (int index = 0; index < sentences.length; index++) {
            String sentence = sentences[index].trim();
            if (!sentence.isBlank()) ranked.add(new Sentence(index, sentence, sentenceScore(sentence, terms)));
        }
        ranked.sort(Comparator.comparingInt(Sentence::score).reversed()
                .thenComparingInt(Sentence::index));
        List<Sentence> selected = ranked.stream().limit(3)
                .sorted(Comparator.comparingInt(Sentence::index)).toList();
        StringBuilder text = new StringBuilder();
        int relevance = 0;
        for (Sentence sentence : selected) {
            if (text.length() > 0) text.append(" ");
            int remaining = 900 - text.length();
            if (remaining <= 0) break;
            text.append(sentence.text(), 0, Math.min(sentence.text().length(), remaining));
            relevance += sentence.score();
        }
        if (text.isEmpty()) text.append(content, 0, Math.min(content.length(), 900));
        return new Excerpt(text.toString(), relevance);
    }

    private static Set<String> queryTerms(String query) {
        Set<String> terms = new LinkedHashSet<>();
        for (String token : query.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (token.length() >= 3 && !STOP_WORDS.contains(token)) terms.add(token);
        }
        return terms;
    }

    private static int sentenceScore(String sentence, Set<String> terms) {
        String normalized = sentence.toLowerCase(Locale.ROOT);
        int score = 0;
        for (String term : terms) if (normalized.contains(term)) score += 10;
        return score;
    }

    private static int sourceScore(int rank, String url, String domain, String title, String publishedAt) {
        int score = Math.max(0, 100 - rank * 5);
        if (url.toLowerCase(Locale.ROOT).startsWith("https://")) score += 5;
        if (domain.endsWith(".gov")) score += 30;
        else if (domain.endsWith(".edu")) score += 20;
        else if (domain.endsWith(".org")) score += 8;
        if (title.toLowerCase(Locale.ROOT).contains("official")) score += 8;
        if (!publishedAt.isBlank()) score += 5;
        return score;
    }

    private static List<String> qualitySignals(Candidate candidate) {
        List<String> signals = new ArrayList<>();
        if (candidate.url().toLowerCase(Locale.ROOT).startsWith("https://")) signals.add("https");
        if (candidate.domain().endsWith(".gov")) signals.add("government-domain");
        if (candidate.domain().endsWith(".edu")) signals.add("education-domain");
        if (candidate.domain().endsWith(".org")) signals.add("organization-domain");
        if (candidate.title().toLowerCase(Locale.ROOT).contains("official")) signals.add("official-title");
        if (!candidate.publishedAt().isBlank()) signals.add("publication-date-present");
        return List.copyOf(signals);
    }

    private static String domain(String url) {
        try {
            String host = URI.create(url).getHost();
            if (host == null) return "";
            String normalized = host.toLowerCase(Locale.ROOT);
            if (normalized.startsWith("www.")) normalized = normalized.substring(4);
            String[] labels = normalized.split("\\.");
            if (labels.length < 2) return normalized;
            int start = labels.length - 2;
            if (labels.length >= 3 && labels[labels.length - 1].length() == 2
                    && Set.of("ac", "co", "com", "gov", "net", "org").contains(labels[labels.length - 2])) {
                start = labels.length - 3;
            }
            return String.join(".", java.util.Arrays.copyOfRange(labels, start, labels.length));
        } catch (Exception error) {
            return "";
        }
    }

    private static int integer(Object value, int fallback) {
        if (value instanceof Number number) return number.intValue();
        try {
            return value == null ? fallback : Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException error) {
            return fallback;
        }
    }

    private static String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String usefulMessage(Exception error) {
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }

    private static Map<String, Object> integerProperty(String description, int minimum, int maximum) {
        return Map.of("type", "integer", "description", description,
                "minimum", minimum, "maximum", maximum);
    }

    record ResearchRequest(String query, int maxSources, int minSources, String language,
                           String timeRange, int safeSearch, List<String> categories) {
        static ResearchRequest from(Map<String, Object> arguments, int defaultMax, int defaultMin) {
            String query = string(arguments.get("query")).trim();
            if (query.isBlank()) throw new IllegalArgumentException("query is required");
            int maxSources = bounded(arguments.get("max_sources"), defaultMax, 1, 5, "max_sources");
            int minFallback = Math.min(defaultMin, maxSources);
            int minSources = bounded(arguments.get("min_sources"), minFallback, 1, 5, "min_sources");
            if (minSources > maxSources) {
                throw new IllegalArgumentException("min_sources must not exceed max_sources");
            }
            String language = string(arguments.get("language")).trim();
            if (language.isBlank()) language = "auto";
            String timeRange = string(arguments.get("time_range")).trim().toLowerCase(Locale.ROOT);
            if (!timeRange.isBlank() && !List.of("day", "month", "year").contains(timeRange)) {
                throw new IllegalArgumentException("time_range must be day, month, or year");
            }
            int safeSearch = bounded(arguments.get("safe_search"), 1, 0, 2, "safe_search");
            return new ResearchRequest(query, maxSources, minSources, language,
                    timeRange, safeSearch, stringList(arguments.get("categories")));
        }

        private static int bounded(Object value, int fallback, int minimum, int maximum, String name) {
            int parsed;
            try {
                parsed = value == null ? fallback : Integer.parseInt(String.valueOf(value));
            } catch (NumberFormatException error) {
                throw new IllegalArgumentException(name + " is invalid");
            }
            if (parsed < minimum || parsed > maximum) {
                throw new IllegalArgumentException(name + " must be between " + minimum + " and " + maximum);
            }
            return parsed;
        }

        private static List<String> stringList(Object value) {
            if (!(value instanceof List<?> list)) return List.of();
            return list.stream().map(WebResearchToolProvider::string)
                    .map(String::trim).filter(item -> !item.isBlank()).limit(8).toList();
        }
    }

    private record Candidate(int rank, String title, String url, String domain, String publishedAt,
                             String provider, String engine, int sourceScore) {}

    private record FetchOutcome(Candidate candidate, Map<String, Object> evidence, String error) {}

    private record Excerpt(String text, int relevanceScore) {}

    private record Sentence(int index, String text, int score) {}
}
