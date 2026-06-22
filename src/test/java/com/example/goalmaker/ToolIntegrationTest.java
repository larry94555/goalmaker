package com.example.goalmaker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolIntegrationTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void skillIsExposedAsAFunctionTool(@TempDir Path tempDir) throws Exception {
        Path skill = tempDir.resolve("skills/example/SKILL.md");
        Files.createDirectories(skill.getParent());
        Files.writeString(skill, """
                ---
                name: summarize_text
                description: Summarize supplied text.
                parameters:
                  type: object
                  properties:
                    text:
                      type: string
                  required: [text]
                ---
                Summarize the supplied text in one sentence.
                """);
        SkillToolProvider skills = new SkillToolProvider(mapper);
        skills.reload(tempDir.resolve("skills"));
        McpToolProvider mcp = new McpToolProvider(mapper);
        ToolCatalog catalog = new ToolCatalog(skills, mcp);
        catalog.refresh();

        List<Map<String, Object>> specifications = catalog.specifications();
        assertEquals(1, specifications.size());
        @SuppressWarnings("unchecked")
        Map<String, Object> function = (Map<String, Object>) specifications.get(0).get("function");
        assertEquals("skill_summarize_text", function.get("name"));
        String result = catalog.execute("skill_summarize_text", Map.of("text", "Long text"));
        assertTrue(result.contains("Summarize the supplied text"));
        assertTrue(result.contains("Long text"));
    }

    @Test
    void localMcpServerAdvertisesAndExecutesTool(@TempDir Path tempDir) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Path config = tempDir.resolve("mcp.json");
        mapper.writeValue(config.toFile(), Map.of("mcpServers", Map.of("demo", Map.of(
                "command", java,
                "args", List.of("-cp", System.getProperty("java.class.path"), FakeMcpServer.class.getName())))));
        McpToolProvider mcp = new McpToolProvider(mapper);
        try {
            mcp.start(config);
            assertEquals(1, mcp.tools().size());
            ToolDefinition tool = mcp.tools().get(0);
            assertEquals("mcp_demo_echo", tool.name());
            assertEquals("echoed", tool.executor().execute(Map.of("text", "hello")));
            assertTrue(Files.isDirectory(tempDir.resolve("logs")));
        } finally {
            mcp.stop();
        }
    }

    @Test
    void llamaRequestIncludesToolsOnlyWhenProvided() {
        List<Map<String, Object>> specifications = List.of(Map.of(
                "type", "function",
                "function", Map.of("name", "skill_echo", "description", "Echo",
                        "parameters", Map.of("type", "object", "properties", Map.of()))));
        Map<String, Object> withTools = LlamaClient.requestBody("model", List.of(), 100, true, specifications);
        Map<String, Object> withoutTools = LlamaClient.requestBody("model", List.of(), 100, true, List.of());

        assertEquals(specifications, withTools.get("tools"));
        assertEquals("auto", withTools.get("tool_choice"));
        assertFalse(withoutTools.containsKey("tools"));
        assertFalse(withoutTools.containsKey("tool_choice"));
    }

    @Test
    void llamaExecutesToolCallAndReturnsFinalAnswer(@TempDir Path tempDir) throws Exception {
        Path skill = tempDir.resolve("skills/example/SKILL.md");
        Files.createDirectories(skill.getParent());
        Files.writeString(skill, """
                ---
                name: summarize_text
                description: Summarize supplied text.
                parameters:
                  type: object
                  properties:
                    text: {type: string}
                ---
                Return a concise summary of the supplied text.
                """);
        SkillToolProvider skills = new SkillToolProvider(mapper);
        skills.reload(tempDir.resolve("skills"));
        ToolCatalog catalog = new ToolCatalog(skills, new McpToolProvider(mapper));
        catalog.refresh();

        List<Map<String, Object>> requests = new ArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> request = mapper.readValue(exchange.getRequestBody(), Map.class);
            requests.add(request);
            Map<String, Object> message = new LinkedHashMap<>();
            message.put("role", "assistant");
            if (requests.size() == 1) {
                message.put("tool_calls", List.of(Map.of(
                        "id", "call_1",
                        "type", "function",
                        "function", Map.of("name", "skill_summarize_text",
                                "arguments", "{\"text\":\"hello\"}"))));
            } else {
                message.put("content", "final answer");
            }
            byte[] response = mapper.writeValueAsBytes(Map.of("choices", List.of(Map.of("message", message))));
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            LlamaClient llama = new LlamaClient(mapper, catalog);
            ReflectionTestUtils.setField(llama, "host", "127.0.0.1");
            ReflectionTestUtils.setField(llama, "port", server.getAddress().getPort());
            ReflectionTestUtils.setField(llama, "model", "test-model");
            ReflectionTestUtils.setField(llama, "cachePrompt", true);
            ReflectionTestUtils.setField(llama, "maxTokens", 100);
            ReflectionTestUtils.setField(llama, "maxToolIterations", 3);

            assertEquals("final answer", llama.prompt("summarize hello"));
        } finally {
            server.stop(0);
        }

        assertEquals(2, requests.size());
        assertTrue(requests.get(0).containsKey("tools"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> secondMessages =
                (List<Map<String, Object>>) requests.get(1).get("messages");
        Map<String, Object> toolMessage = secondMessages.stream()
                .filter(message -> "tool".equals(message.get("role"))).findFirst().orElseThrow();
        assertEquals("call_1", toolMessage.get("tool_call_id"));
        assertTrue(String.valueOf(toolMessage.get("content")).contains("Return a concise summary"));
    }
}
