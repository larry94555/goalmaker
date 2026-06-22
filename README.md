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
out plan steps through the built-in web search, discovered skills, and MCP tools. Classification, clarification, and plan-generation
calls never receive tools.

Tools are loaded once during application startup. Restart the application after adding or changing a skill,
an MCP server, or `mcp.json`. Tool calls are bounded by `tools.max-iterations` and each local process call by
`tools.timeout-seconds` in `application.properties`.

Only install skills and MCP servers you trust. Their commands run locally with the same operating-system
permissions as goalmaker.

### Web Search

The built-in `web_search` tool is always registered and requires one string argument named `query`. For every
request classified as `request-info`, the intermediary explicitly directs the main model to call this tool
before answering.

`web_search` has the same search behavior as `mini`: it queries DuckDuckGo's HTML endpoint, follows redirects,
and returns up to six ranked results with titles, decoded destination URLs, and snippets. Search results are
fenced as untrusted external content before being returned to the model, including a warning when common
prompt-injection language is detected.

No API key is required. The endpoint defaults to `https://html.duckduckgo.com/html/` and can be changed with
`web.search.endpoint` in `application.properties`, primarily for testing or a compatible local proxy.

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
