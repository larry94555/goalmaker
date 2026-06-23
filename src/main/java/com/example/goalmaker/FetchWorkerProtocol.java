package com.example.goalmaker;

import java.util.Map;

final class FetchWorkerProtocol {
    static final String RESPONSE_PREFIX = "GOALMAKER_FETCH_RESULT:";

    private FetchWorkerProtocol() {}

    record Request(Map<String, Object> arguments, FetchSettings settings) {}

    record Response(Map<String, Object> payload, String error) {
        static Response success(Map<String, Object> payload) {
            return new Response(payload, "");
        }

        static Response failure(String error) {
            return new Response(Map.of(), error);
        }
    }
}
