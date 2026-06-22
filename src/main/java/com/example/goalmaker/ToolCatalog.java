package com.example.goalmaker;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class ToolCatalog {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ToolCatalog.class);

    private final SkillToolProvider skills;
    private final McpToolProvider mcp;
    private final WebSearchToolProvider webSearch;
    private final WebFetchToolProvider webFetch;
    private final Map<String, ToolDefinition> tools = new LinkedHashMap<>();

    @Autowired
    public ToolCatalog(SkillToolProvider skills, McpToolProvider mcp, WebSearchToolProvider webSearch,
                       WebFetchToolProvider webFetch) {
        this.skills = skills;
        this.mcp = mcp;
        this.webSearch = webSearch;
        this.webFetch = webFetch;
    }

    ToolCatalog(SkillToolProvider skills, McpToolProvider mcp) {
        this(skills, mcp, null, null);
    }

    ToolCatalog(SkillToolProvider skills, McpToolProvider mcp, WebSearchToolProvider webSearch) {
        this(skills, mcp, webSearch, null);
    }

    @PostConstruct
    public synchronized void refresh() {
        tools.clear();
        if (webSearch != null) webSearch.tools().forEach(this::register);
        if (webFetch != null) webFetch.tools().forEach(this::register);
        skills.tools().forEach(this::register);
        mcp.tools().forEach(this::register);
        log.info("[tools] exposing {} tool(s) to the model", tools.size());
    }

    public synchronized boolean isEmpty() {
        return tools.isEmpty();
    }

    public synchronized boolean contains(String name) {
        return tools.containsKey(name);
    }

    public synchronized List<Map<String, Object>> specifications() {
        List<Map<String, Object>> specifications = new ArrayList<>();
        for (ToolDefinition tool : tools.values()) {
            specifications.add(Map.of("type", "function", "function", Map.of(
                    "name", tool.name(),
                    "description", tool.description(),
                    "parameters", tool.parameters())));
        }
        return specifications;
    }

    public String execute(String name, Map<String, Object> arguments) {
        ToolDefinition tool;
        synchronized (this) {
            tool = tools.get(name);
        }
        if (tool == null) return "ERROR: unknown tool " + name;
        try {
            log.info("[tools] calling {} source={} arguments={}", name, tool.source(), arguments);
            String result = tool.executor().execute(arguments == null ? Map.of() : arguments);
            String normalized = result == null || result.isBlank() ? "(tool completed with no output)" : result;
            return tool.untrusted() ? UntrustedContent.wrap(name, normalized) : normalized;
        } catch (Exception error) {
            log.warn("[tools] {} failed: {}", name, error.getMessage());
            return "ERROR: " + error.getMessage();
        }
    }

    private void register(ToolDefinition tool) {
        if (tools.putIfAbsent(tool.name(), tool) != null) {
            log.warn("[tools] duplicate tool name ignored: {}", tool.name());
        }
    }
}
