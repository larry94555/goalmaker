package com.example.goalmaker;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class PromptController {
    private final LlamaClient llama;
    private final Intermediary intermediary;

    public PromptController(LlamaClient llama, Intermediary intermediary) {
        this.llama = llama;
        this.intermediary = intermediary;
    }

    @PostMapping("/prompt")
    public Map<String, String> prompt(@RequestBody PromptRequest request) throws Exception {
        if (request.prompt() == null || request.prompt().isBlank()) {
            throw new IllegalArgumentException("prompt is required");
        }
        Intermediary.IntermediaryResult result = intermediary.intercept(request.prompt());
        if (!result.proceed()) return Map.of("response", result.response());
        return Map.of("response", llama.prompt(result.prompt(), result.requiredTool()));
    }

    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ExceptionHandler(IllegalArgumentException.class)
    public Map<String, String> badRequest(IllegalArgumentException error) {
        return Map.of("error", error.getMessage());
    }

    public record PromptRequest(String prompt) {}
}
