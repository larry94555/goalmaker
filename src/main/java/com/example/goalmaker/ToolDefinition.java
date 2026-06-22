package com.example.goalmaker;

import java.util.Map;

record ToolDefinition(
        String name,
        String description,
        Map<String, Object> parameters,
        String source,
        Executor executor) {

    @FunctionalInterface
    interface Executor {
        String execute(Map<String, Object> arguments) throws Exception;
    }
}
