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
            public String prompt(String text) {
                calls.add("prompt");
                return "Unchanged response";
            }
        };
        Intermediary intermediary = new Intermediary(llama) {
            @Override
            public String categorize(String prompt) {
                calls.add("categorize");
                return "signal";
            }
        };
        PromptController controller = new PromptController(llama, intermediary);

        Map<String, String> response = controller.prompt(new PromptController.PromptRequest("Hello"));

        assertEquals(Map.of("response", "Unchanged response"), response);
        assertEquals(List.of("categorize", "prompt"), calls);
    }
}
