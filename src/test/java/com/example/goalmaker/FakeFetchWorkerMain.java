package com.example.goalmaker;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;

public final class FakeFetchWorkerMain {
    private FakeFetchWorkerMain() {}

    public static void main(String[] args) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        BufferedReader input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String line;
        while ((line = input.readLine()) != null) {
            FetchWorkerProtocol.Request request = mapper.readValue(line, FetchWorkerProtocol.Request.class);
            String url = String.valueOf(request.arguments().get("url"));
            if (url.contains("hang")) {
                Thread.sleep(30_000);
                continue;
            }
            if (url.contains("crash")) Runtime.getRuntime().halt(17);
            if (url.contains("large")) {
                System.out.println(FetchWorkerProtocol.RESPONSE_PREFIX + "x".repeat(20_000));
                System.out.flush();
                continue;
            }
            Map<String, Object> payload = Map.of(
                    "url", url,
                    "worker_pid", ProcessHandle.current().pid(),
                    "content", "worker response");
            System.out.println(FetchWorkerProtocol.RESPONSE_PREFIX
                    + mapper.writeValueAsString(FetchWorkerProtocol.Response.success(payload)));
            System.out.flush();
        }
    }
}
