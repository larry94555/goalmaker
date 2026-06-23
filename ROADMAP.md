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

### Better document extraction

`web_fetch` now handles public HTML, text, and PDF documents. HTML extraction uses bounded readability scoring
and captures canonical URLs, title, author, publication and modification dates, language, and metadata conflicts.
PDF extraction uses PDFBox under byte, page, character, and time budgets and never executes embedded active
content.

RFC 9309 robots rules are checked for every document and redirect origin, cached with bounded storage, and
reported in fetch provenance. Research evidence now preserves content type, extraction method, canonical URL,
author, dates, page counts, and metadata conflicts. Deterministic tests cover representative HTML, multilingual
text, metadata conflicts, robots exclusions and caching, valid and malformed PDFs, byte and page limits,
redirects, private targets, and provenance propagation.

### Process-isolated fetch workers

Web fetching and HTML/PDF parsing now run outside Spring Boot in a bounded pool of dedicated JVM workers.
Workers have configured heap, metaspace, code-cache, processor, wall-clock, and output limits. A crash, timeout,
or protocol violation discards the worker, and the pool creates a clean replacement without taking down the
application. Healthy workers are reused to preserve concurrency and robots caches.

Each fetch uses a request-local pinned DNS resolver that validates public addresses once and supplies those same
addresses to a direct, no-proxy HTTP connection. Destination ports are allowlisted, decompressed bytes are bounded, and
one time and HTTP-request budget covers robots checks, retries, redirects, downloads, and parsing. Fetch and
research provenance report the active network policy and process-isolation limits.

Tests cover DNS rebinding simulation, private addresses, unapproved ports, redirects, shared request exhaustion,
slow streams, compressed expansion, parser-process crashes, wall-clock termination, oversized worker output,
worker reuse, and automatic recovery.

## Next Recommended Change

### 1. Container-enforced worker sandbox

Add an optional Docker worker mode with hard total-RSS, CPU, PID, filesystem, capability, and network-egress
controls while retaining the portable JVM worker as the no-Docker fallback. Restrict container egress to DNS and
public TCP ports 80/443 and expose worker health and restart counters.

This is the highest-value remaining security improvement because JVM heap and wall-clock controls do not cap all
native memory or provide an operating-system network boundary. The current process worker contains parser crashes
and pins validated DNS results, but a container can enforce the policy even if a parser or dependency is
compromised.

Completion criteria:

- provide explicit `process` and `docker` worker modes with process mode as a portable fallback
- run the Docker worker read-only with dropped capabilities, no new privileges, bounded RSS/CPU/PIDs, and tmpfs
- enforce DNS plus public HTTP(S)-only egress independently of application-level checks
- report worker mode, health, restarts, and resource-limit terminations
- preserve the current worker protocol and structured fetch result contract
- test container startup, policy enforcement, crash recovery, and fallback behavior

## Later Priorities

### 2. Claim-level agreement and conflict analysis

Use the local model to extract comparable claims from evidence, identify material agreement or contradiction,
and attach source references to each finding. Keep retrieval facts separate from model judgments and expose
uncertainty explicitly.

### 3. Search quality evaluation

Maintain a versioned set of factual, current, technical, multilingual, and adversarial queries. Track provider
availability, precision, source diversity, latency, cache effectiveness, and citation correctness over time.

### 4. Optional independent local index

Evaluate YaCy or a focused local crawler for private corpora, intranet search, and resilience when public search
providers are unavailable. Keep it optional because crawling and index maintenance have substantial resource cost.
