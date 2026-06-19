package com.example.goalmaker;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IntermediaryTest {
    @Test
    void categorizesSignal() {
        AtomicInteger calls = new AtomicInteger();
        LlamaClient llama = new LlamaClient(new ObjectMapper()) {
            @Override
            String complete(List<Map<String, Object>> messages, int responseTokens) {
                calls.incrementAndGet();
                return "signal";
            }
        };
        Intermediary intermediary = new Intermediary(llama);

        assertEquals("signal", intermediary.categorize("Thanks"));
        assertEquals(1, calls.get());
    }

    @Test
    void categorizesRequestAndItsMixedGoalTypes() {
        AtomicInteger calls = new AtomicInteger();
        LlamaClient llama = new LlamaClient(new ObjectMapper()) {
            @Override
            String complete(List<Map<String, Object>> messages, int responseTokens) {
                return calls.getAndIncrement() == 0
                        ? "request"
                        : "goal: request-action, request-info";
            }
        };
        Intermediary intermediary = new Intermediary(llama);

        assertEquals("request", intermediary.categorize("What is the capital of France?"));
        assertEquals(2, calls.get());
    }

    @Test
    void normalizesGoalsInCanonicalOrderWithoutDuplicates() {
        assertEquals("request-state, request-action, request-info",
                Intermediary.normalizeGoals("request-info, request-state, request-action, request-state"));
    }

    @Test
    void logsRequestCategoryAndGoalTogether() {
        AtomicInteger calls = new AtomicInteger();
        LlamaClient llama = new LlamaClient(new ObjectMapper()) {
            @Override
            String complete(List<Map<String, Object>> messages, int responseTokens) {
                return calls.getAndIncrement() == 0 ? "request" : "request-info";
            }
        };
        Logger logger = (Logger) LoggerFactory.getLogger(Intermediary.class);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);

        try {
            new Intermediary(llama).categorize("What is the capital of France?");
        } finally {
            logger.detachAppender(logs);
        }

        assertTrue(logs.list.stream().map(ILoggingEvent::getFormattedMessage).anyMatch(message ->
                message.equals("[intermediary] category=request goal: request-info "
                        + "prompt=What is the capital of France?")));
    }

    @Test
    void goalClassifierTreatsCreatingCodeAsAnActionRatherThanAnInformationRequest() {
        List<List<Map<String, Object>>> calls = new java.util.ArrayList<>();
        LlamaClient llama = new LlamaClient(new ObjectMapper()) {
            @Override
            String complete(List<Map<String, Object>> messages, int responseTokens) {
                calls.add(messages);
                return calls.size() == 1 ? "request" : "request-action";
            }
        };

        new Intermediary(llama).categorize(
                "Create a typescript equivalent of the code at c:\\users\\larry\\github\\goalmaker");

        String goalInstructions = calls.get(1).get(0).get("content").toString();
        assertTrue(goalInstructions.contains("Creating, writing, translating, converting"));
        assertTrue(goalInstructions.contains("not supporting steps"));
        assertTrue(goalInstructions.contains("Create a TypeScript equivalent"));
        assertTrue(goalInstructions.contains("-> request-action"));
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
}
