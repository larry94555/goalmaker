# goalmaker

A minimal Spring Boot application that starts and supervises `llama-server` using the same defaults and command-line flags as `mini`, then exposes a single prompt endpoint.

## Run

Requirements: Java 17+, Maven (or the included Maven wrapper), and `llama-server.exe` on `PATH` or in this directory.

```bat
run.bat
```

The first run downloads the default Qwen 2.5 3B GGUF model. Progress is written to `llama-server.log`. Once the application is ready, use another terminal:

```bat
prompt.bat "Write a one-sentence project goal"
```

The HTTP API is `POST http://localhost:8080/prompt` with JSON such as `{"prompt":"Hello"}`.

If a request does not have clear completion criteria, the response contains clarification questions and the
request is held in memory instead of being executed. Send the answer through `prompt.bat` (or `POST /prompt`)
again. Clarifications are merged and reassessed until the goal is clear; `cancel`, `never mind`, or `move on`
closes the pending request. Clear requests receive a logged high-level plan before the normal model response.
Each plan is also saved as YAML under `goals` using
`plan_<concise_goal_title>_<yyyyMMdd_HHmmss_microseconds>.yml`.
