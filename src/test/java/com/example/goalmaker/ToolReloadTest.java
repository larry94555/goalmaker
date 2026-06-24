package com.example.goalmaker;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolReloadTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void reloadActivatesSkillAddedAfterStartup(@TempDir Path tempDir) throws Exception {
        Path skillsDir = tempDir.resolve("skills");
        Files.createDirectories(skillsDir);
        ToolCatalog catalog = catalog(skillsDir, tempDir);
        assertFalse(catalog.contains("skill_added_helper"));

        writeSkill(skillsDir);
        ToolCatalog.ReloadResult result = catalog.reload();

        assertTrue(result.added().contains("skill_added_helper"));
        assertTrue(catalog.contains("skill_added_helper"));
        String output = catalog.execute("skill_added_helper", Map.of("text", "hello"));
        assertTrue(output.contains("process the supplied text"));
        assertTrue(output.contains("hello"));

        // A second reload with no new files adds and removes nothing.
        ToolCatalog.ReloadResult again = catalog.reload();
        assertTrue(again.added().isEmpty());
        assertTrue(again.removed().isEmpty());
        assertTrue(catalog.contains("skill_added_helper"));
    }

    @Test
    void adminEndpointReportsReloadResult(@TempDir Path tempDir) throws Exception {
        Path skillsDir = tempDir.resolve("skills");
        Files.createDirectories(skillsDir);
        ToolCatalog catalog = catalog(skillsDir, tempDir);
        writeSkill(skillsDir);
        ToolsAdminController controller = new ToolsAdminController(catalog);

        ResponseEntity<Map<String, Object>> response = controller.reload();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        Map<String, Object> body = response.getBody();
        assertNotNull(body);
        assertEquals(Boolean.TRUE, body.get("reloaded"));
        assertTrue(((List<?>) body.get("added")).contains("skill_added_helper"));
    }

    @Test
    void adminEndpointForbiddenWhenDisabled(@TempDir Path tempDir) {
        ToolCatalog catalog = catalog(tempDir.resolve("skills"), tempDir);
        ToolsAdminController controller = new ToolsAdminController(catalog);
        ReflectionTestUtils.setField(controller, "enabled", false);

        ResponseEntity<Map<String, Object>> response = controller.reload();

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().containsKey("error"));
    }

    private ToolCatalog catalog(Path skillsDir, Path tempDir) {
        SkillToolProvider skills = new SkillToolProvider(mapper);
        ReflectionTestUtils.setField(skills, "skillsDir", skillsDir.toString());
        skills.load();
        McpToolProvider mcp = new McpToolProvider(mapper);
        ReflectionTestUtils.setField(mcp, "configFile", tempDir.resolve("absent-mcp.json").toString());
        ToolCatalog catalog = new ToolCatalog(skills, mcp);
        catalog.refresh();
        return catalog;
    }

    private static void writeSkill(Path skillsDir) throws Exception {
        Path skill = skillsDir.resolve("added-helper/SKILL.md");
        Files.createDirectories(skill.getParent());
        Files.writeString(skill, """
                ---
                name: added_helper
                description: Added after startup.
                parameters:
                  type: object
                  properties:
                    text: {type: string}
                ---
                Use this helper to process the supplied text.
                """);
    }
}
