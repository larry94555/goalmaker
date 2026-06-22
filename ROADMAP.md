# Web Search Roadmap

This roadmap contains only significant improvements that preserve token-free external web access.

## Next Recommended Change

### 1. Deterministic research and corroboration pipeline

Build a `web_research` tool that performs search, fetches the strongest results, reranks evidence, and returns a
compact evidence bundle with citations and conflicts. Require two independent sources for mutable factual claims
when available. This is the next recommended change because it would make evidence quality deterministic instead
of depending on the model to choose which search results to fetch.

Completion criteria:

- rank results using relevance, freshness, domain diversity, and source quality signals
- fetch a bounded number of pages concurrently with per-domain limits
- identify supporting, conflicting, and insufficient evidence
- return citation-ready excerpts with title, URL, publication date, and retrieval time
- fail explicitly when reliable corroboration cannot be found

## Later Priorities

### 2. Query-aware specialized providers

Route current-news queries to GDELT, factual entity queries to MediaWiki/Wikidata, scholarly queries to arXiv,
and archival URL lookups to Common Crawl. Merge these results with SearXNG while preserving provider provenance.

### 3. Managed SearXNG lifecycle and health

Add startup health checks, provider readiness reporting, failure metrics, and optional GoalMaker-managed SearXNG
startup. Surface provider status without delaying every query when the local service is intentionally disabled.

### 4. Better document extraction

Add PDF text extraction, metadata and publication-date detection, canonical URL handling, robots-policy support,
and a readability-oriented HTML extractor with per-site fallback rules.

### 5. Stronger fetch isolation

Move web fetching into a restricted process or container with network egress controls. Pin DNS resolution through
the connection to close rebinding gaps, restrict ports, and apply total request budgets across redirects.

### 6. Search quality evaluation

Maintain a versioned set of factual, current, technical, multilingual, and adversarial queries. Track provider
availability, precision, source diversity, latency, cache effectiveness, and citation correctness over time.

### 7. Optional independent local index

Evaluate YaCy or a focused local crawler for private corpora, intranet search, and resilience when public search
providers are unavailable. Keep it optional because crawling and index maintenance have substantial resource cost.
