package com.example.goalmaker;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class FakeMcpServer {
    private static final Pattern ID = Pattern.compile("\\\"id\\\"\\s*:\\s*(\\d+)");

    public static void main(String[] args) throws Exception {
        BufferedReader input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String line;
        while ((line = input.readLine()) != null) {
            Integer id = id(line);
            if (line.contains("\"method\":\"initialize\"") && id != null) {
                reply(id, "{\"protocolVersion\":\"2024-11-05\",\"capabilities\":{},"
                        + "\"serverInfo\":{\"name\":\"fake\",\"version\":\"1\"}}");
            } else if (line.contains("\"method\":\"tools/list\"") && id != null) {
                reply(id, "{\"tools\":[{\"name\":\"echo\",\"description\":\"Echo input\","
                        + "\"inputSchema\":{\"type\":\"object\",\"properties\":{"
                        + "\"text\":{\"type\":\"string\"}},\"required\":[\"text\"]}}]}");
            } else if (line.contains("\"method\":\"tools/call\"") && id != null) {
                reply(id, "{\"content\":[{\"type\":\"text\",\"text\":\"echoed\"}]}");
            }
        }
    }

    private static Integer id(String json) {
        Matcher matcher = ID.matcher(json);
        return matcher.find() ? Integer.valueOf(matcher.group(1)) : null;
    }

    private static void reply(int id, String result) {
        System.out.println("{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":" + result + "}");
        System.out.flush();
    }
}
