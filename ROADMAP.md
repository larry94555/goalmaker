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

## Next Recommended Change

### 1. Query-aware specialized providers

Route current-news queries to GDELT, factual entity queries to MediaWiki/Wikidata, scholarly queries to arXiv,
and archival URL lookups to Common Crawl. Normalize these token-free sources into the existing search result and
evidence formats, then merge them with SearXNG while preserving provider provenance.

This is the next recommended high-value change because specialized structured sources improve freshness,
authority, and coverage where general web ranking and HTML extraction are weakest.

Completion criteria:

- classify queries into general, current-news, factual-entity, scholarly, and archival intents
- route each intent to appropriate token-free providers with bounded fallback behavior
- normalize provider-specific dates, identifiers, URLs, snippets, and provenance
- deduplicate specialized and general-web results before evidence selection
- apply provider-specific rate limits, retries, and caching
- evaluate result relevance and source diversity against the existing general-search baseline

## Later Priorities

### 2. Managed SearXNG lifecycle and health

Add startup health checks, provider readiness reporting, failure metrics, and optional GoalMaker-managed SearXNG
startup. Surface provider status without delaying every query when the local service is intentionally disabled.

### 3. Better document extraction

Add PDF text extraction, metadata and publication-date detection, canonical URL handling, robots-policy support,
and a readability-oriented HTML extractor with per-site fallback rules.

### 4. Stronger fetch isolation

Move web fetching into a restricted process or container with network egress controls. Pin DNS resolution through
the connection to close rebinding gaps, restrict ports, and apply total request budgets across redirects.

### 5. Claim-level agreement and conflict analysis

Use the local model to extract comparable claims from evidence, identify material agreement or contradiction,
and attach source references to each finding. Keep retrieval facts separate from model judgments and expose
uncertainty explicitly.

### 6. Search quality evaluation

Maintain a versioned set of factual, current, technical, multilingual, and adversarial queries. Track provider
availability, precision, source diversity, latency, cache effectiveness, and citation correctness over time.

### 7. Optional independent local index

Evaluate YaCy or a focused local crawler for private corpora, intranet search, and resilience when public search
providers are unavailable. Keep it optional because crawling and index maintenance have substantial resource cost.
