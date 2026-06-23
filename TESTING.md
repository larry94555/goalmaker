# Web Search Testing

This guide tests GoalMaker's token-free web-search stack through user prompts, operational exercises, and the
deterministic Maven suite. Public websites can change or throttle requests, so deterministic tests are the source
of truth for edge cases; prompt checks validate the complete application and local model tool loop.

## Prerequisites

From `C:\Users\larry\github\goalmaker`, start local SearXNG:

```bat
docker compose -f docker-compose.searxng.yml up -d
```

If Docker is not on `PATH`, use Docker Desktop's executable directly:

```bat
"C:\Program Files\Docker\Docker\resources\bin\docker.exe" compose -f docker-compose.searxng.yml up -d
```

Start GoalMaker in one terminal:

```bat
run.bat
```

Use a second terminal for prompts. Confirm search health first:

```bat
curl.exe -s http://localhost:8080/health/web-search
```

Expected: `status` is `healthy` or `degraded`, `configured` is `true`, and `search_permitted` is `true`.
Public-provider tests still work through fallbacks when SearXNG is unavailable.

## Prompt Tests

### 1. General multi-source research

```bat
ask.bat "Find the current stable Spring Boot release. Give the version and release date, cite at least two independent sources, and disclose any source disagreement."
```

Expected: the request uses `web_research` before answering, cites fetched sources, and reports when the
independent-source threshold is not met. It should use claim-level analysis when available and disclose its
uncertainty or conflicts. The application log should show `web_research` as the first tool call.

### 2. Current news and GDELT routing

```bat
ask.bat "What are the most important verified developments in commercial fusion energy during the last month? Compare at least three independent sources and include publication dates."
```

Expected: current-news intent adds GDELT to general search. A temporary GDELT failure appears as provider
diagnostics and does not prevent an answer from other sources.

### 3. Factual entity research with MediaWiki and Wikidata

```bat
ask.bat "What is the capital of France, what is its official French name, and what evidence supports both facts? Cite two independent sources."
```

Expected: factual-entity intent routes to MediaWiki and Wikidata and blends their records with general results.

### 4. Scholarly research with arXiv

```bat
ask.bat "Find three recent arXiv papers about retrieval-augmented generation evaluation. For each, give the title, authors, publication date, and the specific evaluation problem studied."
```

Expected: scholarly intent adds arXiv results, retains provider provenance, and fetches useful source text when
the source permits it.

### 5. Archival discovery with Common Crawl

```bat
ask.bat "Find Common Crawl capture records for https://example.com and report the capture timestamp, status, MIME type, and archive index used. Do not claim that archived page content was downloaded."
```

Expected: archival intent uses the latest Common Crawl index and returns capture metadata. GoalMaker does not
download WARC content.

### 6. Blended intents, deduplication, and provenance

```bat
ask.bat "Research this month's news about quantum error correction and compare it with relevant recent arXiv papers. Deduplicate repeated links, identify each source type, and cite three independent domains."
```

Expected: current-news and scholarly providers are blended with general results. Repeated URLs are normalized,
and no single provider should fill every result position when alternatives exist.

### 7. Language, recency, category, and safe-search controls

```bat
ask.bat "Use web research in French with a one-month recency filter, strict safe search, and the science category to explain recent French research on battery recycling. After that required research call, inspect page 2 with web_search and cite the French-language sources."
```

Expected: the model supplies `language`, `time_range`, `safe_search`, and `categories` controls to
`web_research`, then uses the `page` control on `web_search`. SearXNG honors supported controls; specialized
providers use the controls they support.

### 8. Readability-oriented HTML extraction

```bat
ask.bat "Research the history and purpose of the HTML article element. Fetch a detailed source page, summarize only its main article content, and report the page title, canonical URL, author, publication date, modification date, language, and extraction method when available."
```

Expected: navigation, scripts, page chrome, advertisements, and related-content blocks are excluded from the
evidence. Missing metadata should be reported as unavailable, not invented.

### 9. PDF text and metadata extraction

```bat
ask.bat "Fetch and inspect the exact PDF https://www.w3.org/WAI/ER/tests/xhtml/testfiles/resources/pdf/dummy.pdf . State its extracted text and report content type, extraction method, title, author, page count, pages extracted, and any truncation reason."
```

Expected: the answer includes `Dummy PDF file`, `application/pdf`, `pdfbox`, and page provenance. Ask the model
to report `fetch_isolation.mode=worker-process`, `fetch_policy.dns_pinned=true`, allowed ports, and total budgets.
Title or author may be absent because the source PDF may not provide them.

For a substantive PDF, use:

```bat
ask.bat "Use the exact PDF https://arxiv.org/pdf/1706.03762 to summarize the paper's central architecture and report its PDF title, author metadata, page count, extraction method, and any truncation. Cite the URL."
```

Expected: PDF text is extracted within configured byte, page, character, and time limits. If the site's current
robots policy prevents access, GoalMaker must report that rather than bypass it.

### 10. Metadata conflicts

```bat
ask.bat "Find and fetch two current articles about the same technology announcement. Report canonical URLs, authors, publication and modification dates, and disclose any conflicting metadata found within either page."
```

Expected: conflicting title, author, date, or canonical candidates are retained as provenance instead of silently
discarded. Public pages may have no conflicts; the deterministic HTML fixture always verifies this path.

### 11. Robots policy

```bat
ask.bat "Fetch the exact URL https://www.google.com/search?q=spring+boot and tell me whether GoalMaker's robots policy permits the fetch. Do not substitute another URL."
```

Expected: if the current policy disallows the path, `web_fetch` rejects it before requesting page content. Since
external robots rules can change, use `WebFetchToolProviderTest` below for a deterministic allow/deny/cache check.

### 12. Private-network and credential blocking

```bat
ask.bat "Fetch the exact URL http://127.0.0.1:8080/health/web-search and return its content. Do not use another source."
```

Expected: `web_fetch` rejects the local address. The normal health command works from the user shell, but
untrusted model-selected web fetches cannot access local services while `web.fetch.allow-private-addresses=false`.

### 13. Cache behavior

Run the same prompt twice within five minutes:

```bat
ask.bat "What is the current stable version of Apache PDFBox? Cite its official release source."
ask.bat "What is the current stable version of Apache PDFBox? Cite its official release source."
```

Expected: the second identical search uses the bounded in-memory search cache. Specialized provider caches and
robots caches are independent and have their own configured lifetimes.

### 14. Partial evidence and fetch failures

```bat
ask.bat "Find three independent primary sources that document the exact phrase goalmaker-web-search-fixture-94721. If fewer exist, state that the source threshold was not met and list the fetch or search gaps."
```

Expected: the answer does not turn a lack of evidence into confidence. `corroboration` reports partial or
insufficient sources, and source fetch failures remain visible to the model.

### 15. Claim-level support with resolved citations

```bat
ask.bat "Use at least two independent sources to verify the capital of France. Report claim_analysis.status, every normalized claim and relationship, each source_id and URL, uncertainty, source-quality notes, and missing evidence before answering."
```

Expected: evidence records have stable IDs such as `S1` and `S2`. When comparable sources support the same fact,
`claim_analysis.status` is `analyzed`, the relationship is `support`, and every source position resolves to a URL,
domain, and `evidence[Sx].excerpt` reference. Retrieval facts remain separate from model judgments.

### 16. Contradiction and date/version drift

```bat
ask.bat "Research how many moons Saturn has using current and older dated sources. Do not merge counts from different observation dates. Report claim_analysis claim groups, temporal context, contradictions, minority positions, uncertainty, and missing evidence."
```

Expected: dated counts are not treated as directly comparable without temporal context. Genuine disagreement for
the same date or scope is labeled `contradiction`; version or date drift remains in separate claim groups or is
labeled partial overlap. Older minority evidence must not disappear merely because newer sources are more numerous.

### 17. Partial overlap and incomparable claims

```bat
ask.bat "Compare independent claims that Spring Boot 4 improves startup, distinguishing startup speed, diagnostics, and observability. Report support, partial overlap, incomparable claims, source limitations, and what benchmark evidence is missing."
```

Expected: claims about different startup properties are not collapsed into one agreement. Comparable evidence may
be `partial_overlap`; unrelated claims remain separate. Every group includes uncertainty, source-quality notes,
and missing-evidence text.

### 18. Explicit analyzer disable fallback

Set `web.research.claim-analysis.enabled=false`, restart GoalMaker, and run prompt 15 again.

Expected: research evidence is still returned, `claim_analysis.status=disabled`, corroboration does not claim
semantic agreement, and the main model compares cited evidence directly. Restore the setting to `true` afterward.

## Outage And Recovery Tests

Stop SearXNG:

```bat
docker compose -f docker-compose.searxng.yml stop searxng
```

Run a general prompt:

```bat
ask.bat "Find the official Java 17 documentation for records and summarize the definition with a citation."
```

Expected: after the configured failure threshold, the SearXNG circuit opens and the request continues through
DuckDuckGo plus applicable specialized providers. Check health:

```bat
curl.exe -s http://localhost:8080/health/web-search
```

Expected: `status` is `unavailable`, circuit diagnostics are present, and configured fallbacks are listed.

Restart SearXNG and wait for the next background probe:

```bat
docker compose -f docker-compose.searxng.yml start searxng
```

Expected: health returns to `healthy` or `degraded` without restarting GoalMaker, and later searches use SearXNG.

To test optional managed startup, stop SearXNG, set `web.search.searxng-manage=true`, restart GoalMaker, and poll
the health endpoint. Expected: Spring Boot starts without waiting for search, health moves from `starting` to
`healthy` or `degraded`, and Compose output is written to `searxng-compose.log`.

## Fetch Isolation Tests

### Worker process and policy provenance

```bat
ask.bat "Fetch https://www.w3.org/WAI/ER/tests/xhtml/testfiles/resources/pdf/dummy.pdf and report the fetch_isolation and fetch_policy objects exactly, followed by the extracted text."
```

Expected: `fetch_isolation.mode` is `worker-process` with the default configuration, or `worker-docker` when
Docker mode is selected. Enabled is true; pool, resource, wall-clock, output, and worker status fields are present.
`fetch_policy.dns_pinned` is true, allowed ports are 80 and 443, and total time and HTTP request budgets are
present.

While GoalMaker is running, worker JVMs can be observed with PowerShell:

```powershell
Get-CimInstance Win32_Process | Where-Object CommandLine -Match 'FetchWorkerMain' | Select-Object ProcessId, CommandLine
```

Workers are started lazily. Run a fetch prompt first. Healthy workers remain available for reuse and are stopped
when GoalMaker exits.

### Docker-enforced worker

Set the following and restart GoalMaker:

```properties
web.fetch.worker.mode=docker
web.fetch.worker.docker.auto-build=true
```

Run the policy-provenance prompt again. Expected: `fetch_isolation.mode=worker-docker`; total memory, CPU, PID,
tmpfs, read-only-root, capability, no-new-privileges, firewall, build, and worker-health fields are present. The
first request may build the image and writes progress to `fetch-worker-docker-build.log`. A matching source
fingerprint skips subsequent builds.

Inspect the running container after a fetch:

```powershell
docker ps --filter "label=com.example.goalmaker.component=fetch-worker"
docker inspect $(docker ps -q --filter "label=com.example.goalmaker.component=fetch-worker")
```

Expected HostConfig: read-only root, memory and memory-swap limits, NanoCpus, PidsLimit, `/tmp` tmpfs,
`no-new-privileges`, all capabilities dropped with only startup `NET_ADMIN`, `SETUID`, `SETGID`, and `SETPCAP`
added. The entrypoint removes those capabilities before Java starts.

Run the real Docker integration test:

```bat
.\mvnw.cmd -B -ntp "-Dgoalmaker.live-docker-worker-test=true" "-Dtest=DockerFetchWorkerLiveTest" test
```

Expected: the image builds or matches its fingerprint, a public fetch succeeds, HostConfig limits are verified,
Java reports UID 65532, zero effective capabilities, and `NoNewPrivs=1`, a root-filesystem write fails, and a
deliberately permissive request to `host.docker.internal` never reaches the host test server because the container
firewall rejects private/high-port egress.

### Unapproved destination port

```bat
ask.bat "Fetch the exact URL https://example.com:8443/ and do not substitute another URL."
```

Expected: the fetch is rejected with `url port 8443 is not allowed` before a connection is attempted.

### Private address through DNS

```bat
ask.bat "Fetch the exact URL http://localhost/ and do not use another source."
```

Expected: the fetch is rejected because the hostname resolves to a private or local address. The deterministic
suite also simulates a DNS answer changing from public to private and verifies that only the first validated
address set is ever supplied to the connection layer.

### Worker failure and recovery

Crash, hang, oversized output, and recovery paths use a deterministic fake worker rather than an external web
page. Run:

```bat
.\mvnw.cmd -B -ntp "-Dtest=FetchWorkerClientTest" test
```

Expected: one healthy worker is reused; a crash, wall-clock timeout, and oversized output each discard that
worker; a clean replacement successfully handles the request immediately following every failure.

### Network and parsing budgets

```bat
.\mvnw.cmd -B -ntp "-Dtest=WebFetchToolProviderTest,PinnedDnsTest,FetchBudgetTest" test
```

Expected: tests pass for shared robots/redirect request exhaustion, slow streaming termination, decompressed-size
limits, redirect loops, private targets, unapproved ports, pinned DNS, PDF limits, and isolated-worker execution.

## Automated Tests

Run the complete deterministic suite. It makes no public-network calls:

```bat
.\mvnw.cmd -B -ntp clean test
```

Run only fetch and research tests while iterating:

```bat
.\mvnw.cmd -B -ntp "-Dtest=ClaimAnalysisServiceTest,WebFetchToolProviderTest,WebResearchToolProviderTest,WebSearchToolProviderTest,LexicalRankerTest,FetchWorkerClientTest,PinnedDnsTest,FetchBudgetTest" test
```

Run the opt-in public integration suite with local SearXNG available:

```bat
.\mvnw.cmd -B -ntp "-Dgoalmaker.live-web-test=true" test
```

Public providers may throttle requests; a live failure is diagnostic and should be compared with the deterministic
suite before treating it as a regression.

Docker sandbox verification is intentionally separate because it requires Docker and may build an image:

```bat
.\mvnw.cmd -B -ntp "-Dgoalmaker.live-docker-worker-test=true" "-Dtest=DockerFetchWorkerLiveTest" test
```

## Coverage Review

This checklist was reviewed against the current web-search implementation and roadmap.

| Feature | Prompt or operation | Deterministic coverage |
| --- | --- | --- |
| Mandatory research-first information flow | Prompt 1 | `IntermediaryFlowTest`, `PromptControllerTest` |
| SearXNG JSON search, retries, deduplication, cache | Prompts 1 and 13 | `WebSearchToolProviderTest` |
| DuckDuckGo fallback | Outage test | `WebSearchToolProviderTest` |
| Language, recency, paging, safe search, categories | Prompt 7 | `WebSearchToolProviderTest` request assertions |
| Lexical (BM25) re-ranking of search results | Prompts 1 and 6 | `WebSearchToolProviderTest`, `LexicalRankerTest` |
| Relevance-ranked research candidates and excerpt sentences | Prompts 1 and 6 | `WebResearchToolProviderTest`, `LexicalRankerTest` |
| News via GDELT | Prompt 2 | `SpecializedSearchServiceTest` |
| Entities via MediaWiki and Wikidata | Prompt 3 | `SpecializedSearchServiceTest` |
| Scholarly results via arXiv | Prompt 4 | `SpecializedSearchServiceTest` |
| Archive metadata via Common Crawl | Prompt 5 | `SpecializedSearchServiceTest` |
| Intent routing, blending, provenance, provider failure isolation | Prompt 6 | `SpecializedSearchServiceTest` |
| Provider rate limits and independent caches | Prompts 2 through 6 and 13 | `SpecializedSearchServiceTest` |
| Diverse concurrent fetches and source sufficiency | Prompts 1 and 14 | `WebResearchToolProviderTest` |
| Fetch failures and semantic-analysis fallback | Prompts 1, 14, and 18 | `WebResearchToolProviderTest`, `ClaimAnalysisServiceTest` |
| Stable source IDs and resolved URL/excerpt references | Prompt 15 | `ClaimAnalysisServiceTest`, `WebResearchToolProviderTest` |
| Agreement, contradiction, and minority evidence | Prompts 15 and 16 | `ClaimAnalysisServiceTest` |
| Date/version drift and incomparable claim separation | Prompts 16 and 17 | `ClaimAnalysisServiceTest` |
| Partial overlap, uncertainty, quality, and missing evidence | Prompt 17 | `ClaimAnalysisServiceTest` |
| Invented references, malformed output, and timeout fallback | Prompt 18 | `ClaimAnalysisServiceTest` |
| Readable HTML and multilingual text | Prompt 8 | `WebFetchToolProviderTest` |
| Canonical URL, author, dates, language, metadata conflicts | Prompts 8 and 10 | `WebFetchToolProviderTest` |
| Plain-text extraction | Prompt 1 source dependent | `WebFetchToolProviderTest` |
| PDF text, metadata, page cap, malformed and oversized rejection | Prompt 9 | `WebFetchToolProviderTest` |
| Robots allow, deny, cache, and pre-fetch enforcement | Prompt 11 | `WebFetchToolProviderTest` |
| Redirect validation and redirect limit | Prompt 8 source dependent | `WebFetchToolProviderTest` |
| Private/local target blocking | Prompt 12 | `WebFetchToolProviderTest` |
| Byte, page, character, and parsing-time budgets | Prompt 9 | `WebFetchToolProviderTest`; timeout is enforced in implementation |
| Isolated worker process and process-limit provenance | Fetch isolation prompt | `WebFetchToolProviderTest`, `FetchWorkerClientTest` |
| Worker reuse, crash, timeout, output overflow, and recovery | Worker failure command | `FetchWorkerClientTest` |
| Docker launch hardening and explicit process fallback | Docker worker section | `DockerWorkerCommandTest` |
| Docker memory/CPU/PID/read-only/tmpfs/no-new-privileges controls | Docker worker section | `DockerFetchWorkerLiveTest` |
| Container firewall independent of application policy | Docker worker section | `DockerFetchWorkerLiveTest` |
| Source-fingerprinted managed image build | Docker worker section | `DockerFetchWorkerLiveTest` |
| Worker health, start, failure, and build counters | Fetch isolation prompts | `FetchWorkerClientTest`, `DockerFetchWorkerLiveTest` |
| DNS pinning and rebinding resistance | Private-address prompt | `PinnedDnsTest` |
| Public destination-port allowlist | Unapproved-port prompt | `PinnedDnsTest` |
| One total time and HTTP-request budget | Network budget command | `FetchBudgetTest`, `WebFetchToolProviderTest` |
| Slow streams and decompressed-size expansion | Network budget command | `WebFetchToolProviderTest` |
| Untrusted-content fencing and injection warning | All prompts | `WebFetchToolProviderTest`, `WebSearchToolProviderTest` |
| Health states, metrics, circuit breaker, recovery | Outage test | `SearxngHealthManagerTest`, `WebSearchHealthControllerTest` |
| Optional managed Compose startup | Managed-startup operation | `SearxngHealthManagerTest`, `WebSearchConfigurationTest` |
| Live SearXNG and public-provider compatibility | Live command | `WebResearchLiveTest`, `SpecializedSearchLiveTest`, `SearxngHealthLiveTest` |

The prompt suite covers every user-visible web-search capability. Network-sensitive and process-isolation cases
have deterministic fixtures; Docker enforcement has an opt-in live test. The PDF timeout itself is enforced with
a cancellable bounded future and a parent-enforced worker deadline, while the suite avoids a deliberately
CPU-consuming PDF fixture. The next roadmap priority is a versioned search-quality benchmark with regression
gates, because retrieval and semantic-analysis changes now need repeatable relevance and citation measurements.
