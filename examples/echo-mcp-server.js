const readline = require("node:readline");

const lines = readline.createInterface({ input: process.stdin });

function send(message) {
  process.stdout.write(JSON.stringify(message) + "\n");
}

lines.on("line", (line) => {
  let request;
  try {
    request = JSON.parse(line);
  } catch (error) {
    console.error("Ignoring non-JSON input:", error.message);
    return;
  }

  if (request.method === "initialize") {
    send({
      jsonrpc: "2.0",
      id: request.id,
      result: {
        protocolVersion: request.params?.protocolVersion ?? "2024-11-05",
        capabilities: { tools: {} },
        serverInfo: { name: "goalmaker-echo", version: "1.0.0" }
      }
    });
  } else if (request.method === "tools/list") {
    send({
      jsonrpc: "2.0",
      id: request.id,
      result: {
        tools: [{
          name: "echo",
          description: "Return supplied text.",
          inputSchema: {
            type: "object",
            properties: { text: { type: "string" } },
            required: ["text"]
          }
        }]
      }
    });
  } else if (request.method === "tools/call") {
    const text = String(request.params?.arguments?.text ?? "");
    send({
      jsonrpc: "2.0",
      id: request.id,
      result: { content: [{ type: "text", text }] }
    });
  } else if (request.id !== undefined && !request.method?.startsWith("notifications/")) {
    send({
      jsonrpc: "2.0",
      id: request.id,
      error: { code: -32601, message: `Unknown method: ${request.method}` }
    });
  }
});
