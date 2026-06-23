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

### Managed SearXNG lifecycle and health

GoalMaker now probes configured SearXNG readiness asynchronously at startup and on a bounded background interval.
It distinguishes disabled, starting, healthy, degraded, and unavailable states and exposes timestamps, latency,
failure details, circuit state, and probe/search counters through `/health/web-search`.

Repeated failures open a short circuit so searches immediately continue through DuckDuckGo and specialized
providers. Background probes remain active and restore SearXNG after recovery. Optional Docker Compose startup is
explicitly configurable, waits for readiness without blocking Spring Boot startup, and remains off by default.
Tests cover healthy, slow, malformed, unavailable, recovery, managed-startup, and disabled behavior.

## Next Recommended Change

### 1. Better document extraction

Add robust PDF extraction, metadata and publication-date detection, canonical URL handling, robots-policy
support, and a readability-oriented HTML extractor with bounded per-site fallback rules.

This is the next recommended high-value change because retrieval now has broad and resilient discovery, while
the quality of the final answer is increasingly limited by how accurately `web_fetch` extracts the selected
sources. Better extraction improves evidence quality for nearly every researched request.

Completion criteria:

- extract bounded text and metadata from PDFs without executing active content
- prefer article/main content through a readability-oriented extractor with current selectors as fallback
- detect canonical URLs, title, author, publication date, and modification date when present
- honor explicit robots exclusions and report when extraction is intentionally skipped
- preserve content type, extraction method, page count, and truncation details in fetch provenance
- apply per-document time, byte, page, and character budgets
- test representative HTML, PDF, malformed, oversized, redirect, multilingual, and metadata-conflict cases

## Later Priorities

### 2. Stronger fetch isolation

Move web fetching into a restricted process or container with network egress controls. Pin DNS resolution through
the connection to close rebinding gaps, restrict ports, and apply total request budgets across redirects.

### 3. Claim-level agreement and conflict analysis

Use the local model to extract comparable claims from evidence, identify material agreement or contradiction,
and attach source references to each finding. Keep retrieval facts separate from model judgments and expose
uncertainty explicitly.

### 4. Search quality evaluation

Maintain a versioned set of factual, current, technical, multilingual, and adversarial queries. Track provider
availability, precision, source diversity, latency, cache effectiveness, and citation correctness over time.

### 5. Optional independent local index

Evaluate YaCy or a focused local crawler for private corpora, intranet search, and resilience when public search
providers are unavailable. Keep it optional because crawling and index maintenance have substantial resource cost.
