package com.example.goalmaker;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

public final class FetchWorkerMain {
    private static final int MAX_REQUEST_BYTES = 131_072;

    private FetchWorkerMain() {}

    public static void main(String[] args) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        WebFetchEngine engine = new WebFetchEngine(mapper, new RobotsPolicy());
        while (true) {
            String line = readBoundedLine(MAX_REQUEST_BYTES);
            if (line == null) return;
            FetchWorkerProtocol.Response response;
            try {
                FetchWorkerProtocol.Request request = mapper.readValue(line, FetchWorkerProtocol.Request.class);
                response = FetchWorkerProtocol.Response.success(
                        engine.fetchPayload(request.arguments(), request.settings()));
            } catch (Throwable error) {
                if (error instanceof VirtualMachineError virtualMachineError) throw virtualMachineError;
                response = FetchWorkerProtocol.Response.failure(usefulMessage(error));
            }
            String encoded = mapper.writeValueAsString(response);
            System.out.write((FetchWorkerProtocol.RESPONSE_PREFIX + encoded + "\n")
                    .getBytes(StandardCharsets.UTF_8));
            System.out.flush();
        }
    }

    private static String readBoundedLine(int maximum) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        while (true) {
            int value = System.in.read();
            if (value < 0) return output.size() == 0 ? null : output.toString(StandardCharsets.UTF_8);
            if (value == '\n') return output.toString(StandardCharsets.UTF_8);
            if (value != '\r') output.write(value);
            if (output.size() > maximum) throw new IOException("fetch worker request exceeded size limit");
        }
    }

    private static String usefulMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }
}
