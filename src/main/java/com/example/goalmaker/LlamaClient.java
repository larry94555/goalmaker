package com.example.goalmaker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

@Component
public class LlamaClient {
    @Value("${llama.client-host:127.0.0.1}") private String host;
    @Value("${llama.port:8081}") private int port;
    @Value("${llama.alias:qwen2.5-3b-instruct}") private String model;
    @Value("${llama.cache-prompt:true}") private boolean cachePrompt;
    @Value("${prompt.max-tokens:1024}") private int maxTokens;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper mapper;

    public LlamaClient(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public String prompt(String text) throws Exception {
        return complete(List.of(Map.of("role", "user", "content", text)), maxTokens);
    }

    String complete(List<Map<String, Object>> messages, int responseTokens) throws Exception {
        Map<String, Object> body = Map.of(
                "model", model,
                "messages", messages,
                "max_tokens", responseTokens,
                "cache_prompt", cachePrompt,
                "stream", false);
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
        JsonNode content = root.path("choices").path(0).path("message").path("content");
        if (content.isMissingNode()) throw new IllegalStateException("Unexpected llama-server response: " + response.body());
        return content.asText();
    }
}
