# goalmaker

A minimal Spring Boot application that starts and supervises `llama-server` using the same defaults and command-line flags as `mini`, then exposes a single prompt endpoint.

## Run

Requirements: Java 17+, Maven (or the included Maven wrapper), and `llama-server.exe` on `PATH` or in this directory.

```bat
run.bat
```

The first run downloads the default Qwen 2.5 3B GGUF model. Progress is written to `llama-server.log`. Once the application is ready, use another terminal:

```bat
ask.bat "Write a one-sentence project goal"
```

The HTTP API is `POST http://localhost:8080/prompt` with JSON such as `{"prompt":"Hello"}`.

If a request does not have clear completion criteria, the response contains clarification questions and the
request is held in memory instead of being executed. Send the answer through `ask.bat` (or `POST /prompt`)
again. Clarifications are merged and reassessed until the goal is clear; `cancel`, `never mind`, or `move on`
closes the pending request. Clear requests receive a logged high-level plan before the normal model response.
Each plan is also saved as YAML under `goals` using
`plan_<concise_goal_title>_<yyyyMMdd_HHmmss_microseconds>.yml`.

## Tools

After a request is clear, the intermediary gives the saved plan to the main model. The main model may carry
out plan steps through built-in web research, discovered skills, and MCP tools. Classification, clarification,
and plan-generation calls never receive tools.

Tools are loaded once during application startup. Restart the application after adding or changing a skill,
an MCP server, or `mcp.json`. Tool calls are bounded by `tools.max-iterations` and each local process call by
`tools.timeout-seconds` in `application.properties`.

Only install skills and MCP servers you trust. Their commands run locally with the same operating-system
permissions as goalmaker.

### Web Research

The built-in `web_research`, `web_search`, and `web_fetch` tools require no API token. Every `request-info`
forces `web_research` as the first model tool call. It deterministically searches, selects source-diverse
candidates, fetches pages concurrently, and returns a compact evidence bundle before the model writes an answer.

Search queries are classified deterministically into one or more intents. General web search always remains
available, while current-news queries also use GDELT, factual-entity queries use MediaWiki and Wikidata,
scholarly queries use arXiv, and archival URL lookups use the latest Common Crawl index. Results are normalized,
deduplicated, and blended round-robin with SearXNG or DuckDuckGo so one provider cannot occupy every result slot.
Each result retains its provider and engine provenance. A specialized provider failure is reported in
`provider_notes` and does not prevent the remaining providers from returning results.

`web_research` accepts `query`, `max_sources`, `min_sources`, `language`, `time_range`, `safe_search`, and
SearXNG `categories`. It prefers one source per registrable domain, ranks candidates using search position and
bounded source signals, extracts the most query-relevant sentences, and returns citation-ready evidence with
titles, URLs, domains, publication and retrieval times, ranking details, and fetch failures.

The `corroboration` object reports whether the requested independent-source threshold was met. This is a
structural source check, not a claim that the sources semantically agree. When multiple sources are returned,
the model is required to compare them and disclose material conflicts. When the threshold is not met, the model
must say so and may use `web_search` and `web_fetch` for additional investigation.

`web_search` prefers the structured JSON API of a local SearXNG instance and falls back to DuckDuckGo HTML when
SearXNG is unavailable or returns no results. Query-aware providers are then blended when the classifier selects
them. The tool supports `query`, `max_results`, `language`, `time_range`, `page`, `safe_search`, and SearXNG
`categories`. Results are returned as JSON with detected `query_intents`, rank, provider, engine, title, URL,
snippet, optional publication date, retrieval time, cache status, and provider diagnostics. Transient failures
are retried with bounded backoff, oversized responses are rejected, and successful searches are cached.

Specialized providers have independent cache durations, minimum call intervals, retry overrides, response-size
limits, and endpoint settings under `web.specialized.*` in `application.properties`. Set a provider URL to blank
to disable it. Common Crawl currently supplies capture metadata from its latest published index; it does not
download archived WARC content. Public token-free services may throttle or temporarily reject traffic, so the
general search path remains the bounded fallback.

GoalMaker probes SearXNG asynchronously at startup and every 30 seconds by default. It reports `disabled`,
`starting`, `healthy`, `degraded`, or `unavailable`, along with latency, last success, failure details, circuit
state, and counters at:

```text
GET http://localhost:8080/health/web-search
```

A slow successful response is degraded. After repeated failures, the circuit opens and searches immediately use
DuckDuckGo and applicable specialized providers instead of waiting for a known-unavailable SearXNG request.
Background probes continue and restore SearXNG automatically when it recovers. The compact SearXNG health state
is also included in `web_search` results, including cached results.

Start the included local SearXNG service manually with Docker:

```bat
docker compose -f docker-compose.searxng.yml up -d
```

To let GoalMaker run that command when SearXNG is unavailable at startup, explicitly set:

```properties
web.search.searxng-manage=true
```

Managed startup is off by default. GoalMaker first probes an already-running service, starts the configured
Compose file only when needed, waits for readiness without blocking Spring Boot startup, and writes command output
to `searxng-compose.log`. On Windows it also checks Docker Desktop's standard executable location when `docker`
is not yet on `PATH`. GoalMaker does not stop the container when the application exits. Set
`web.search.searxng-url=` to intentionally disable SearXNG and its probes.

Its JSON API is bound to `127.0.0.1:8888`; it is not exposed to the network. Replace the placeholder secret in
`searxng/settings.yml` before changing that binding. If Docker or SearXNG is unavailable, GoalMaker continues
through DuckDuckGo automatically. See the [SearXNG Search API](https://docs.searxng.org/dev/search_api.html)
for supported query controls.

`web_fetch` follows at most five redirects and accepts public HTTP(S) HTML, text, and PDF documents. A
readability-oriented HTML extractor removes common page noise and prefers semantic article content. It records
available canonical URL, title, author, publication date, modification date, language, content type, extraction
method, and conflicting metadata candidates. PDFBox extracts text and document metadata without executing
embedded active content; PDF downloads, parsing time, pages, and returned characters are independently bounded.

Before fetching a document or redirect target, GoalMaker applies that origin's `robots.txt` rules for the
`goalmaker` user agent using RFC 9309 parsing. Rules are cached for one hour by default. Explicit exclusions are
rejected before document content is requested, and temporary robots failures fail closed. Fetch results report
the robots decision, downloaded byte count, extraction method, page counts, and precise truncation reasons.

The fetcher rejects credentials, unsupported content types, malformed or encrypted PDFs, and URLs resolving to
local, private, link-local, or multicast addresses. Each redirect and robots target is validated before it is
fetched. Keep `web.fetch.allow-private-addresses=false` unless a trusted local integration explicitly requires
otherwise. Fetch and robots limits are configured under `web.fetch.*` in `application.properties`.

Fetching and document parsing run in a persistent pool of dedicated JVM worker processes by default. A worker
has a bounded heap, metaspace and code cache, one advertised processor, a hard parent-enforced wall-clock limit,
and a bounded response protocol. A crash, timeout, malformed protocol response, or oversized output causes that
worker to be terminated; the next fetch starts a clean replacement. Healthy workers are reused so concurrent
research remains practical and robots rules stay cached.

Each fetch resolves a hostname once through a request-local DNS map. The same validated addresses are returned
to OkHttp for every connection attempt during that fetch, preventing a second DNS answer from redirecting the
connection to a private address. Worker HTTP connections bypass system proxies so the pinned destination cannot
be resolved by an intermediary instead. By default only destination ports 80 and 443 are permitted. One total time
budget and one HTTP-request count cover robots checks, retries, redirects, downloads, and parsing together.
Decompressed response bytes are bounded before extraction. Fetch results expose these controls in `fetch_policy`
and `fetch_isolation`, and `web_research` preserves both objects in its evidence provenance.

The worker settings are:

```properties
web.fetch.allowed-ports=80,443
web.fetch.total-budget-seconds=40
web.fetch.max-http-requests=12
web.fetch.worker.enabled=true
web.fetch.worker.pool-size=4
web.fetch.worker.memory-mb=192
web.fetch.worker.active-processors=1
web.fetch.worker.timeout-seconds=45
web.fetch.worker.max-output-bytes=1048576
```

Set `web.fetch.worker.enabled=false` only for trusted debugging; it moves parsing back into the Spring Boot
process. The portable worker boundary limits managed JVM resources but is not an operating-system network or
total-RSS sandbox. Container-enforced CPU, memory, process, filesystem, and egress policy remains a roadmap item.

All research, search, and fetched content is fenced as untrusted external data before reaching the model. Common
prompt-injection phrases receive an additional warning, but the untrusted boundary applies to every result.
Timeouts, retry limits, cache behavior, provider URLs, response sizes, redirects, fetch text limits, worker
resources, allowed ports, total fetch budgets, health intervals, circuit breaking, optional Compose startup,
source thresholds, candidate count, and research concurrency are configurable in `application.properties`.

With local SearXNG running, execute the optional live integration check with:

```bat
.\mvnw.cmd -B -ntp "-Dgoalmaker.live-web-test=true" test
```

This checks SearXNG health/readiness and research plus live MediaWiki/Wikidata, arXiv, and Common Crawl routing.
GDELT parsing and fallback behavior are tested with deterministic fixtures because its public endpoint may
rate-limit CI runs.

See [TESTING.md](TESTING.md) for prompt-based checks, outage and recovery exercises, focused deterministic
tests, and a coverage matrix for every web-search feature.

### Create A Skill

A skill is a directory under `skills` containing `SKILL.md`. Its YAML front matter defines the function tool;
the Markdown body supplies reusable instructions. A skill named `echo` is exposed to the model as
`skill_echo`.

```text
skills/
  echo/
    SKILL.md
    echo.ps1
```

Example `skills/echo/SKILL.md`:

```yaml
---
name: echo
description: Echo text through a trusted local command.
parameters:
  type: object
  properties:
    text:
      type: string
      description: Text to echo.
  required: [text]
command:
  - powershell
  - -NoProfile
  - -ExecutionPolicy
  - Bypass
  - -File
  - echo.ps1
timeoutSeconds: 30
---
Use this skill when the user asks to echo supplied text.
```

The `parameters` value is the JSON Schema sent to `llama-server`. For executable skills, goalmaker starts
`command` in the skill directory, writes the model's JSON arguments to standard input, and returns the combined
standard output and error streams as the tool result. A nonzero exit code or timeout becomes an error result. Prefer a YAML command list;
a command string is also accepted and runs through the platform shell.

Example `echo.ps1`:

```powershell
$arguments = [Console]::In.ReadToEnd() | ConvertFrom-Json
Write-Output $arguments.text
```

Omit `command` to create an instruction-only skill. Calling it returns the Markdown instructions and JSON
arguments to the model. Runnable templates are included under `skills/example`; remove the `.example`
suffixes and move or rename the directory to activate them.

### Create A Local MCP Service

Local MCP services use JSON-RPC over standard input/output. Copy `mcp.example.json` to `mcp.json` and configure
one or more processes:

```json
{
  "mcpServers": {
    "echo": {
      "command": "node",
      "args": ["examples/echo-mcp-server.js"],
      "env": {},
      "protocolVersion": "2024-11-05"
    }
  }
}
```

Each server is started as a child process. Goalmaker sends `initialize`,
`notifications/initialized`, and `tools/list`; model calls are forwarded through `tools/call`. A tool named
`echo` from the server named `local` is exposed as `mcp_local_echo`. Set optional `cwd` and `env` fields when
the service needs a working directory or environment variables. `protocolVersion` is configurable per server
for compatibility with the server implementation.

An MCP process must reserve stdout for one JSON-RPC object per line. Write diagnostics to stderr; goalmaker
redirects them to `logs/mcp-<server>.log`. The runnable `examples/echo-mcp-server.js` demonstrates the complete
minimal handshake, tool discovery, and tool execution contract. See the
[Model Context Protocol documentation](https://modelcontextprotocol.io/) for full server capabilities.

### Model Tool Loop

Available tools are sent to the OpenAI-compatible chat endpoint as function definitions. When the model emits
`tool_calls`, goalmaker executes each named tool, appends its result as a `tool` message, and asks the model to
continue. The loop stops when the model returns a normal answer or reaches `tools.max-iterations`. The execution
prompt explicitly forbids claiming that an action succeeded unless an available tool result confirms it.
