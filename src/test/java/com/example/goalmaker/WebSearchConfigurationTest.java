package com.example.goalmaker;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebSearchConfigurationTest {
    @Test
    void searxngComposeAndSettingsAreParseableAndLoopbackOnly() throws Exception {
        Map<String, Object> compose = load("docker-compose.searxng.yml");
        @SuppressWarnings("unchecked")
        Map<String, Object> services = (Map<String, Object>) compose.get("services");
        @SuppressWarnings("unchecked")
        Map<String, Object> searxng = (Map<String, Object>) services.get("searxng");
        assertEquals("docker.io/searxng/searxng:latest", searxng.get("image"));
        assertEquals(List.of("127.0.0.1:8888:8080"), searxng.get("ports"));

        Map<String, Object> settings = load("searxng/settings.yml");
        @SuppressWarnings("unchecked")
        Map<String, Object> search = (Map<String, Object>) settings.get("search");
        assertTrue(((List<?>) search.get("formats")).contains("json"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> load(String path) throws Exception {
        return new Yaml().loadAs(Files.readString(Path.of(path)), Map.class);
    }
}
