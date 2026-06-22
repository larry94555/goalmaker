package com.example.goalmaker;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IntermediaryFlowTest {
    @Test
    void asksQuestionsThenResolvesClarificationAndCreatesPlan(@TempDir Path tempDir) throws Exception {
        ScriptedLlama llama = new ScriptedLlama(
                "request",
                "request-action",
                "singular-no-monitoring",
                "completion-criteria-clarity:: not fully clear\n"
                        + "completion-criteria-proposal: The TypeScript equivalent preserves behavior.\n"
                        + "completion-criteria-open-issues: Which output directory should be used?",
                "clarification",
                "request",
                "request-action",
                "singular-no-monitoring",
                "completion-criteria-clarity:: fully clear\n"
                        + "completion-criteria-proposal: The equivalent is written to src and preserves behavior.\n"
                        + "completion-criteria-open-issues: NONE",
                "title: TypeScript equivalent\n1. Inspect the source behavior.\n"
                        + "2. Implement the TypeScript equivalent in src.\n"
                        + "3. Verify equivalent behavior.");
        Intermediary intermediary = new Intermediary(llama);
        Path goals = tempDir.resolve("goals");
        intermediary.setGoalsDirectory(goals);
        Logger logger = (Logger) LoggerFactory.getLogger(Intermediary.class);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);

        Intermediary.IntermediaryResult first;
        Intermediary.IntermediaryResult second;
        try {
            first = intermediary.intercept("Create a TypeScript equivalent of the project");
            second = intermediary.intercept("Put it in src and preserve current behavior");
        } finally {
            logger.detachAppender(logs);
        }

        assertFalse(first.proceed());
        assertTrue(first.response().startsWith("Will this completion criterion suffice:"));
        assertTrue(second.proceed());
        assertTrue(second.prompt().contains("Original request:"));
        assertTrue(second.prompt().contains("Put it in src"));
        assertTrue(second.prompt().contains("Every action must be performed through a skill or MCP tool"));
        assertEquals(10, llama.calls.size());
        assertTrue(logs.list.stream().map(ILoggingEvent::getFormattedMessage)
                .anyMatch(message -> message.contains("clarification-state=resolved")));
        assertTrue(logs.list.stream().map(ILoggingEvent::getFormattedMessage)
                .anyMatch(message -> message.contains("high-level-plan:")));
        List<Path> files;
        try (var stream = Files.list(goals)) {
            files = stream.toList();
        }
        assertEquals(1, files.size());
        assertTrue(files.get(0).getFileName().toString()
                .matches("plan_typescript_equivalent_\\d{8}_\\d{6}_\\d{6}\\.yml"));
        String yaml = Files.readString(files.get(0));
        assertTrue(yaml.contains("title: 'TypeScript equivalent'"));
        assertTrue(yaml.contains("completion_criteria: 'The equivalent is written to src and preserves behavior.'"));
        assertTrue(yaml.contains("  - 'Implement the TypeScript equivalent in src.'"));
        Map<String, Object> parsed = new Yaml().load(yaml);
        assertEquals("TypeScript equivalent", parsed.get("title"));
        assertEquals(3, ((List<?>) parsed.get("steps")).size());
    }

    @Test
    void cancellationClosesPendingRequest() {
        ScriptedLlama llama = new ScriptedLlama(
                "request",
                "request-action",
                "singular-no-monitoring",
                "completion-criteria-clarity:: not fully clear\n"
                        + "completion-criteria-proposal: The artifact is created.\n"
                        + "completion-criteria-open-issues: Which artifact should be created?");
        Intermediary intermediary = new Intermediary(llama);

        Intermediary.IntermediaryResult first = intermediary.intercept("Create it");
        Intermediary.IntermediaryResult cancelled = intermediary.intercept("Never mind");

        assertFalse(first.proceed());
        assertFalse(cancelled.proceed());
        assertEquals("The pending request has been cancelled.", cancelled.response());
        assertEquals(4, llama.calls.size());
    }

    private static class ScriptedLlama extends LlamaClient {
        private final Deque<String> responses;
        private final List<List<Map<String, Object>>> calls = new ArrayList<>();

        ScriptedLlama(String... responses) {
            super(new ObjectMapper());
            this.responses = new ArrayDeque<>(List.of(responses));
        }

        @Override
        String complete(List<Map<String, Object>> messages, int responseTokens) {
            calls.add(messages);
            if (responses.isEmpty()) throw new AssertionError("Unexpected llama call " + calls.size());
            return responses.removeFirst();
        }
    }
}
