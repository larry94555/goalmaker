package com.example.goalmaker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class LlamaClient {
    @Value("${llama.client-host:127.0.0.1}") private String host;
    @Value("${llama.port:8081}") private int port;
    @Value("${llama.alias:qwen2.5-3b-instruct}") private String model;
    @Value("${llama.cache-prompt:true}") private boolean cachePrompt;
    @Value("${prompt.max-tokens:1024}") private int maxTokens;
    @Value("${tools.max-iterations:8}") private int maxToolIterations = 8;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper mapper;
    private final ToolCatalog tools;

    @Autowired
    public LlamaClient(ObjectMapper mapper, ToolCatalog tools) {
        this.mapper = mapper;
        this.tools = tools;
    }

    public LlamaClient(ObjectMapper mapper) {
        this(mapper, null);
    }

    public String prompt(String text) throws Exception {
        return prompt(text, "");
    }

    public String prompt(String text, String requiredTool) throws Exception {
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "user", "content", text));
        String required = requiredTool == null ? "" : requiredTool.trim();
        if (tools == null || tools.isEmpty()) {
            if (!required.isBlank()) throw new IllegalStateException("required tool is unavailable: " + required);
            return complete(messages, maxTokens);
        }
        if (!required.isBlank() && !tools.contains(required)) {
            throw new IllegalStateException("required tool is unavailable: " + required);
        }

        List<Map<String, Object>> specifications = tools.specifications();
        for (int iteration = 0; iteration < Math.max(1, maxToolIterations); iteration++) {
            boolean forceRequired = iteration == 0 && !required.isBlank();
            List<Map<String, Object>> available = forceRequired
                    ? specifications.stream().filter(specification -> toolName(specification).equals(required)).toList()
                    : specifications;
            JsonNode assistant = send(messages, maxTokens, available, forceRequired ? "required" : "auto");
            JsonNode calls = assistant.path("tool_calls");
            if (iteration == 0 && !required.isBlank() && !callsRequiredTool(calls, required)) {
                throw new IllegalStateException("model did not call required tool " + required);
            }
            if (!calls.isArray() || calls.isEmpty()) return assistant.path("content").asText("");
            @SuppressWarnings("unchecked")
            Map<String, Object> assistantMessage = mapper.convertValue(assistant, Map.class);
            messages.add(assistantMessage);
            for (int index = 0; index < calls.size(); index++) {
                JsonNode call = calls.get(index);
                String id = call.path("id").asText("call_" + iteration + "_" + index);
                String name = call.path("function").path("name").asText();
                Map<String, Object> arguments = parseArguments(call.path("function").path("arguments"));
                String result = tools.execute(name, arguments);
                Map<String, Object> toolMessage = new LinkedHashMap<>();
                toolMessage.put("role", "tool");
                toolMessage.put("tool_call_id", id);
                toolMessage.put("name", name);
                toolMessage.put("content", result);
                messages.add(toolMessage);
            }
        }
        throw new IllegalStateException("model exceeded " + maxToolIterations + " tool iterations");
    }

    String complete(List<Map<String, Object>> messages, int responseTokens) throws Exception {
        JsonNode message = send(messages, responseTokens, List.of(), null);
        JsonNode content = message.path("content");
        if (content.isMissingNode()) throw new IllegalStateException("Unexpected llama-server response");
        return content.asText();
    }

    private JsonNode send(List<Map<String, Object>> messages, int responseTokens,
                          List<Map<String, Object>> toolSpecifications, Object toolChoice) throws Exception {
        Map<String, Object> body = requestBody(
                model, messages, responseTokens, cachePrompt, toolSpecifications, toolChoice);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://" + host + ":" + port + "/v1/chat/completions"))
                .timeout(Duration.ofMinutes(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("llama-server " + response.statusCode() + ": " + response.body());
        }
        JsonNode root = mapper.readTree(response.body());
        JsonNode message = root.path("choices").path(0).path("message");
        if (message.isMissingNode()) {
            throw new IllegalStateException("Unexpected llama-server response: " + response.body());
        }
        return message;
    }

    static Map<String, Object> requestBody(String model, List<Map<String, Object>> messages,
                                           int responseTokens, boolean cachePrompt,
                                           List<Map<String, Object>> toolSpecifications) {
        return requestBody(model, messages, responseTokens, cachePrompt, toolSpecifications, "auto");
    }

    static Map<String, Object> requestBody(String model, List<Map<String, Object>> messages,
                                           int responseTokens, boolean cachePrompt,
                                           List<Map<String, Object>> toolSpecifications,
                                           Object toolChoice) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", messages);
        body.put("max_tokens", responseTokens);
        body.put("cache_prompt", cachePrompt);
        body.put("stream", false);
        if (toolSpecifications != null && !toolSpecifications.isEmpty()) {
            body.put("tools", toolSpecifications);
            body.put("tool_choice", toolChoice == null ? "auto" : toolChoice);
        }
        return body;
    }

    private static boolean callsRequiredTool(JsonNode calls, String requiredTool) {
        if (!calls.isArray()) return false;
        for (JsonNode call : calls) {
            if (requiredTool.equals(call.path("function").path("name").asText())) return true;
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private static String toolName(Map<String, Object> specification) {
        Object function = specification.get("function");
        if (!(function instanceof Map<?, ?> map)) return "";
        Object name = ((Map<String, Object>) map).get("name");
        return name == null ? "" : String.valueOf(name);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseArguments(JsonNode arguments) {
        try {
            if (arguments.isObject()) return mapper.convertValue(arguments, Map.class);
            if (arguments.isTextual() && !arguments.asText().isBlank()) {
                return mapper.readValue(arguments.asText(), Map.class);
            }
        } catch (Exception ignored) {
            // The tool receives an empty object when the model emits malformed arguments.
        }
        return Map.of();
    }
}
