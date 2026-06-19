package com.example.goalmaker;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

class IntermediaryTest {
    @Test
    void categorizesSignal() {
        Intermediary intermediary = new Intermediary(clientReturning("signal"));

        assertEquals("signal", intermediary.categorize("Thanks"));
    }

    @Test
    void categorizesRequest() {
        Intermediary intermediary = new Intermediary(clientReturning("request"));

        assertEquals("request", intermediary.categorize("What is the capital of France?"));
    }

    @Test
    void classifierFailureDoesNotBlockOriginalRequest() {
        LlamaClient unavailable = new LlamaClient(new ObjectMapper()) {
            @Override
            String complete(List<Map<String, Object>> messages, int responseTokens) {
                throw new IllegalStateException("unavailable");
            }
        };
        Intermediary intermediary = new Intermediary(unavailable);

        assertDoesNotThrow(() -> assertEquals("request", intermediary.categorize("Do the work")));
    }

    private LlamaClient clientReturning(String response) {
        return new LlamaClient(new ObjectMapper()) {
            @Override
            String complete(List<Map<String, Object>> messages, int responseTokens) {
                return response;
            }
        };
    }
}
