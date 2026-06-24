package com.example.goalmaker;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Local admin endpoint that re-scans skills and MCP servers and rebuilds the tool catalog at
 * runtime, so a skill or tool created during a session (for example by the {@code skill-builder}
 * skill) becomes available on the next request without restarting the JVM. The trigger re-reads the
 * same trusted local directories already loaded at startup; it can be disabled with
 * {@code tools.admin-reload-enabled=false}.
 */
@RestController
public class ToolsAdminController {
    private final ToolCatalog tools;

    @Value("${tools.admin-reload-enabled:true}") private boolean enabled = true;

    public ToolsAdminController(ToolCatalog tools) {
        this.tools = tools;
    }

    @PostMapping("/admin/tools/reload")
    public ResponseEntity<Map<String, Object>> reload() {
        if (!enabled) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "tool reload is disabled"));
        }
        ToolCatalog.ReloadResult result = tools.reload();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("reloaded", true);
        body.put("tool_count", result.toolCount());
        body.put("added", result.added());
        body.put("removed", result.removed());
        return ResponseEntity.ok(body);
    }
}
