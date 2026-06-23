package com.example.goalmaker;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PromptControllerTest {
    @Test
    void categorizesBeforeReturningTheOriginalModelResponse() throws Exception {
        List<String> calls = new ArrayList<>();
        LlamaClient llama = new LlamaClient(new ObjectMapper()) {
            @Override
            public String prompt(String text, String requiredTool) {
                calls.add("prompt:" + requiredTool);
                return "Unchanged response";
            }
        };
        Intermediary intermediary = new Intermediary(llama) {
            @Override
            public IntermediaryResult intercept(String prompt) {
                calls.add("intercept");
                return IntermediaryResult.proceed(prompt);
            }
        };
        PromptController controller = new PromptController(llama, intermediary);

        Map<String, String> response = controller.prompt(new PromptController.PromptRequest("Hello"));

        assertEquals(Map.of("response", "Unchanged response"), response);
        assertEquals(List.of("intercept", "prompt:"), calls);
    }

    @Test
    void returnsClarificationWithoutCallingTheMainModel() throws Exception {
        List<String> calls = new ArrayList<>();
        LlamaClient llama = new LlamaClient(new ObjectMapper()) {
            @Override
            public String prompt(String text, String requiredTool) {
                calls.add("prompt");
                return "must not be returned";
            }
        };
        Intermediary intermediary = new Intermediary(llama) {
            @Override
            public IntermediaryResult intercept(String prompt) {
                calls.add("intercept");
                return IntermediaryResult.intervene("Which output directory should be used?");
            }
        };
        PromptController controller = new PromptController(llama, intermediary);

        Map<String, String> response = controller.prompt(new PromptController.PromptRequest("Create it"));

        assertEquals(Map.of("response", "Which output directory should be used?"), response);
        assertEquals(List.of("intercept"), calls);
    }

    @Test
    void passesRequiredToolToTheMainModel() throws Exception {
        List<String> calls = new ArrayList<>();
        LlamaClient llama = new LlamaClient(new ObjectMapper()) {
            @Override
            public String prompt(String text, String requiredTool) {
                calls.add(requiredTool);
                return "researched response";
            }
        };
        Intermediary intermediary = new Intermediary(llama) {
            @Override
            public IntermediaryResult intercept(String prompt) {
                return IntermediaryResult.proceed(prompt, "web_research");
            }
        };

        Map<String, String> response = new PromptController(llama, intermediary)
                .prompt(new PromptController.PromptRequest("What changed?"));

        assertEquals(Map.of("response", "researched response"), response);
        assertEquals(List.of("web_research"), calls);
    }
}
