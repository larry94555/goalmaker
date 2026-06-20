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
                return switch (calls.getAndIncrement()) {
                    case 0 -> "request";
                    case 1 -> "goal: request-action, request-info";
                    default -> "singular-no-monitoring";
                };
            }
        };
        Intermediary intermediary = new Intermediary(llama);

        assertEquals("request", intermediary.categorize("What is the capital of France?"));
        assertEquals(3, calls.get());
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
                        + "management: singular-no-monitoring "
                        + "prompt=What is the capital of France?")));
    }

    @Test
    void stateRequestUsesModelSelectedManagementType() {
        AtomicInteger calls = new AtomicInteger();
        LlamaClient llama = new LlamaClient(new ObjectMapper()) {
            @Override
            String complete(List<Map<String, Object>> messages, int responseTokens) {
                return switch (calls.getAndIncrement()) {
                    case 0 -> "request";
                    case 1 -> "request-state, request-action";
                    default -> "ongoing-monitoring-within-bounds";
                };
            }
        };
        Logger logger = (Logger) LoggerFactory.getLogger(Intermediary.class);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);

        try {
            new Intermediary(llama).categorize(
                    "Monitor the service until Friday and restart it whenever it becomes unhealthy");
        } finally {
            logger.detachAppender(logs);
        }

        assertEquals(3, calls.get());
        assertTrue(logs.list.stream().map(ILoggingEvent::getFormattedMessage).anyMatch(message ->
                message.contains("category=request goal: request-state, request-action "
                        + "management: ongoing-monitoring-within-bounds")));
    }

    @Test
    void normalizesEveryManagementType() {
        assertEquals("singular-no-monitoring",
                Intermediary.normalizeManagement("singular-no-monitoring"));
        assertEquals("singular-monitoring",
                Intermediary.normalizeManagement("Type: singular-monitoring"));
        assertEquals("ongoing-no-monitoring",
                Intermediary.normalizeManagement("ongoing-no-monitoring"));
        assertEquals("ongoing-monitoring-within-bounds",
                Intermediary.normalizeManagement("ONGOING-MONITORING-WITHIN-BOUNDS"));
        assertEquals("ongoing-monitoring-without-bounds",
                Intermediary.normalizeManagement("ongoing-monitoring-without-bounds"));
    }

    @Test
    void recurringActionUsesOngoingNoMonitoring() {
        AtomicInteger calls = new AtomicInteger();
        LlamaClient llama = new LlamaClient(new ObjectMapper()) {
            @Override
            String complete(List<Map<String, Object>> messages, int responseTokens) {
                return switch (calls.getAndIncrement()) {
                    case 0 -> "request";
                    case 1 -> "request-action";
                    default -> "ongoing-no-monitoring";
                };
            }
        };
        Logger logger = (Logger) LoggerFactory.getLogger(Intermediary.class);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);

        try {
            new Intermediary(llama).categorize("Email a status report every Monday");
        } finally {
            logger.detachAppender(logs);
        }

        assertEquals(3, calls.get());
        assertTrue(logs.list.stream().map(ILoggingEvent::getFormattedMessage).anyMatch(message ->
                message.contains("goal: request-action management: ongoing-no-monitoring")));
    }

    @Test
    void goalClassifierTreatsCreatingCodeAsAnActionRatherThanAnInformationRequest() {
        List<List<Map<String, Object>>> calls = new java.util.ArrayList<>();
        LlamaClient llama = new LlamaClient(new ObjectMapper()) {
            @Override
            String complete(List<Map<String, Object>> messages, int responseTokens) {
                calls.add(messages);
                return switch (calls.size()) {
                    case 1 -> "request";
                    case 2 -> "request-action";
                    default -> "singular-no-monitoring";
                };
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
