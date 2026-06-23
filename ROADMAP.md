# Web Search Roadmap

This roadmap contains only significant improvements that preserve token-free external web access.

## Completed Foundation

### Deterministic research and source corroboration

The `web_research` tool now searches, favors source-diverse candidates, fetches pages concurrently, extracts
query-relevant evidence, and returns citation-ready source records. It reports fetch failures and whether the
configured independent-source threshold was met. Information requests require this tool before the model can
answer, while `web_search` and `web_fetch` remain available for follow-up investigation.

Source-threshold sufficiency is deliberately distinct from semantic agreement. The model must compare returned
evidence and disclose conflicts; GoalMaker does not label two sources as agreeing merely because both were found.

### Query-aware specialized providers

Queries are now classified into general, current-news, factual-entity, scholarly, and archival intents. GDELT,
MediaWiki/Wikidata, arXiv, and Common Crawl are routed automatically and blended with the general search path.
Provider results use the existing normalized result format, retain provenance, and are deduplicated before
evidence selection.

Each specialized provider has bounded retries, independent rate limiting and caching, response limits, and
non-fatal fallback behavior. Deterministic tests cover intent selection, provider parsing, caching, failure
isolation, and source-diversity improvement over the general-search baseline. Opt-in tests verify the supported
public endpoints without making the normal suite network-dependent.

## Next Recommended Change

### 1. Managed SearXNG lifecycle and health

Add startup health checks, provider readiness reporting, failure metrics, and optional GoalMaker-managed SearXNG
startup. Surface provider status without delaying every query when the local service is intentionally disabled.

This is the next recommended high-value change because SearXNG remains the broadest token-free discovery source.
Fast readiness detection and actionable diagnostics reduce avoidable fallback latency and make search failures
substantially easier to identify and recover from.

Completion criteria:

- probe configured SearXNG readiness at startup and on a bounded background interval
- distinguish disabled, starting, healthy, degraded, and unavailable states
- expose status, last success, latency, and recent failure reason through an application health endpoint
- skip known-unavailable SearXNG calls during a short circuit-breaker window while retaining recovery probes
- optionally start the repository Docker Compose service when explicitly enabled
- keep DuckDuckGo and specialized providers available throughout SearXNG startup or failure
- test healthy, slow, malformed, unavailable, recovery, and intentionally-disabled states

## Later Priorities

### 2. Better document extraction

Add PDF text extraction, metadata and publication-date detection, canonical URL handling, robots-policy support,
and a readability-oriented HTML extractor with per-site fallback rules.

### 3. Stronger fetch isolation

Move web fetching into a restricted process or container with network egress controls. Pin DNS resolution through
the connection to close rebinding gaps, restrict ports, and apply total request budgets across redirects.

### 4. Claim-level agreement and conflict analysis

Use the local model to extract comparable claims from evidence, identify material agreement or contradiction,
and attach source references to each finding. Keep retrieval facts separate from model judgments and expose
uncertainty explicitly.

### 5. Search quality evaluation

Maintain a versioned set of factual, current, technical, multilingual, and adversarial queries. Track provider
availability, precision, source diversity, latency, cache effectiveness, and citation correctness over time.

### 6. Optional independent local index

Evaluate YaCy or a focused local crawler for private corpora, intranet search, and resilience when public search
providers are unavailable. Keep it optional because crawling and index maintenance have substantial resource cost.
