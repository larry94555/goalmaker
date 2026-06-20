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
                    case 2 -> "singular-no-monitoring";
                    default -> "completion-criteria-clarity:: fully clear\n"
                            + "completion-criteria: The requested information is returned.";
                };
            }
        };
        Intermediary intermediary = new Intermediary(llama);

        assertEquals("request", intermediary.categorize("What is the capital of France?"));
        assertEquals(4, calls.get());
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
                return switch (calls.getAndIncrement()) {
                    case 0 -> "request";
                    case 1 -> "request-info";
                    default -> "completion-criteria-clarity:: fully clear\n"
                            + "completion-criteria: The capital of France is returned.";
                };
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
        assertTrue(logs.list.stream().map(ILoggingEvent::getFormattedMessage).anyMatch(message ->
                message.equals("[intermediary] completion-criteria-clarity:: fully clear "
                        + "completion-criteria: The capital of France is returned. "
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
                    case 2 -> "ongoing-monitoring-within-bounds";
                    default -> "completion-criteria-clarity:: fully clear\n"
                            + "completion-criteria: Monitoring continues through Friday.";
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

        assertEquals(4, calls.get());
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
    void parsesClearCompletionCriteria() {
        Intermediary.CompletionAssessment assessment = Intermediary.parseCompletionAssessment("""
                completion-criteria-clarity:: fully clear
                completion-criteria-proposal: The requested file exists and the build succeeds.
                completion-criteria-open-issues: NONE
                """);

        assertTrue(assessment.fullyClear());
        assertEquals("The requested file exists and the build succeeds.", assessment.detail());
    }

    @Test
    void parsesCompletionCriteriaOpenIssues() {
        Intermediary.CompletionAssessment assessment = Intermediary.parseCompletionAssessment("""
                completion-criteria-clarity:: not fully clear
                completion-criteria-proposal: NONE
                completion-criteria-open-issues: Which directory should contain the output? | Must it compile?
                """);

        assertTrue(!assessment.fullyClear());
        assertEquals("Which directory should contain the output? | Must it compile? "
                + "The answers will allow a concrete completion criterion to be proposed.", assessment.detail());
    }

    @Test
    void formatsProposedCompletionCriterionAsAConfirmationQuestion() {
        Intermediary.CompletionAssessment assessment = Intermediary.parseCompletionAssessment("""
                completion-criteria-clarity:: not fully clear
                completion-criteria-proposal: A TypeScript equivalent preserves behavior and builds successfully.
                completion-criteria-open-issues: Which output location and compatibility target should be used?
                """);

        assertEquals("Will this completion criterion suffice: A TypeScript equivalent preserves behavior "
                + "and builds successfully? If not, which output location and compatibility target should "
                + "be used?", assessment.detail());
    }

    @Test
    void completionClassifierExcludesResponseTimingAndGuidesOpenQuestions() {
        List<List<Map<String, Object>>> calls = new java.util.ArrayList<>();
        LlamaClient llama = new LlamaClient(new ObjectMapper()) {
            @Override
            String complete(List<Map<String, Object>> messages, int responseTokens) {
                calls.add(messages);
                return switch (calls.size()) {
                    case 1 -> "request";
                    case 2 -> "request-info";
                    default -> "completion-criteria-clarity:: fully clear\n"
                            + "completion-criteria: The correct capital of France is returned.";
                };
            }
        };

        new Intermediary(llama).categorize("What is the capital of France?");

        String instructions = calls.get(2).get(0).get("content").toString();
        assertTrue(instructions.contains("Completely ignore time-to-completion"));
        assertTrue(instructions.contains("Never ask when a response is needed"));
        assertTrue(instructions.contains("completion-criteria-proposal:"));
        assertTrue(instructions.contains("specific direct questions"));
    }

    @Test
    void secondPassProposesCriteriaForConcreteRequest() {
        AtomicInteger calls = new AtomicInteger();
        LlamaClient llama = new LlamaClient(new ObjectMapper()) {
            @Override
            String complete(List<Map<String, Object>> messages, int responseTokens) {
                return switch (calls.getAndIncrement()) {
                    case 0 -> "request";
                    case 1 -> "request-action";
                    case 2 -> "singular-no-monitoring";
                    case 3 -> "completion-criteria-clarity:: not fully clear\n"
                            + "completion-criteria-proposal: NONE\n"
                            + "completion-criteria-open-issues: Which output location should be used?";
                    default -> "completion-criteria-proposal: A TypeScript equivalent preserves source "
                            + "behavior and builds successfully.\n"
                            + "completion-criteria-open-issues: Which output location should be used?";
                };
            }
        };
        Logger logger = (Logger) LoggerFactory.getLogger(Intermediary.class);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);

        try {
            new Intermediary(llama).categorize(
                    "Create a TypeScript equivalent of the code in C:\\repo");
        } finally {
            logger.detachAppender(logs);
        }

        assertEquals(5, calls.get());
        assertTrue(logs.list.stream().map(ILoggingEvent::getFormattedMessage).anyMatch(message ->
                message.contains("Will this completion criterion suffice: A TypeScript equivalent "
                        + "preserves source behavior and builds successfully? If not, which output "
                        + "location should be used?")));
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
                    case 2 -> "ongoing-no-monitoring";
                    default -> "completion-criteria-clarity:: not fully clear\n"
                            + "completion-criteria-proposal: A status report is emailed every Monday.\n"
                            + "completion-criteria-open-issues: Who receives the report?";
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

        assertEquals(4, calls.get());
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
                    case 3 -> "singular-no-monitoring";
                    case 4 -> "completion-criteria-clarity:: not fully clear\n"
                            + "completion-criteria-proposal: NONE\n"
                            + "completion-criteria-open-issues: Where should the TypeScript output be written?";
                    default -> "completion-criteria-proposal: A TypeScript equivalent preserves behavior.\n"
                            + "completion-criteria-open-issues: Where should it be written?";
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
