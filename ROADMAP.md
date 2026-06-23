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

### Container-enforced worker sandbox

GoalMaker now provides explicit `process` and `docker` fetch-worker modes. Docker mode preserves the worker
protocol while adding hard total-memory/swap, CPU, PID, file-descriptor, read-only-filesystem, tmpfs, and
no-new-privileges controls. It uses no host mounts and automatically removes stopped containers.

The container entrypoint installs an outbound firewall, then changes to an unprivileged UID and erases its
capability bounding set before Java starts. IPv6 is disabled; DNS is allowed only to Docker-provided resolvers;
private and reserved IPv4 ranges are rejected; and all remaining egress is limited to TCP ports 80 and 443.
Application-level pinned DNS, robots policy, destination-port checks, and total request budgets remain active as
an independent layer.

Docker images can be built automatically on first use. A source fingerprint label avoids rebuilding a matching
image, while build logs and worker health/restart counters remain visible. Tests audit hardened launch flags and
exercise a real Docker image, HostConfig limits, successful public fetching, and an intentionally permissive
application request that is still blocked by the container firewall. The live test also verifies the Java UID,
empty effective capability set, no-new-privileges state, and read-only root filesystem.

## Next Recommended Change

### 1. Claim-level agreement and conflict analysis

Use the local model to extract comparable claims from fetched evidence, identify material agreement or
contradiction, and attach source references to every finding. Keep retrieved facts separate from model judgments,
represent uncertainty explicitly, and never infer agreement merely because the source threshold was met.

This is the highest-value remaining answer-quality improvement because discovery, extraction, and isolation are
now broad and resilient, while corroboration still measures source structure rather than whether sources support
the same claims.

Completion criteria:

- extract concise, normalized claims with source and excerpt references
- group only genuinely comparable claims and label support, contradiction, partial overlap, or insufficient evidence
- separate deterministic retrieval facts from local-model interpretations in the result schema
- preserve minority and conflicting evidence instead of collapsing to a majority answer
- include uncertainty, source quality, and missing-evidence notes for every assessment
- test clear agreement, direct contradiction, date/version drift, incomparable claims, and unsupported model output

## Later Priorities

### 2. Search quality evaluation

Maintain a versioned set of factual, current, technical, multilingual, and adversarial queries. Track provider
availability, precision, source diversity, latency, cache effectiveness, and citation correctness over time.

### 3. Optional independent local index

Evaluate YaCy or a focused local crawler for private corpora, intranet search, and resilience when public search
providers are unavailable. Keep it optional because crawling and index maintenance have substantial resource cost.
