package com.example.goalmaker;

import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Set;

final class FetchUrlPolicy {
    private final PinnedDns dns;
    private final Set<Integer> allowedPorts;

    FetchUrlPolicy(PinnedDns dns, Set<Integer> allowedPorts) {
        this.dns = dns;
        this.allowedPorts = allowedPorts == null ? Set.of() : Set.copyOf(allowedPorts);
    }

    void validate(URI uri) throws Exception {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!("http".equals(scheme) || "https".equals(scheme)) || uri.getHost() == null) {
            throw new IllegalArgumentException("url must be an absolute http(s) URL");
        }
        if (uri.getUserInfo() != null) throw new IllegalArgumentException("url must not contain credentials");
        int port = uri.getPort() >= 0 ? uri.getPort() : "https".equals(scheme) ? 443 : 80;
        if (!allowedPorts.isEmpty() && !allowedPorts.contains(port)) {
            throw new IllegalArgumentException("url port " + port + " is not allowed");
        }
        try {
            dns.lookup(uri.getHost());
        } catch (UnknownHostException error) {
            String message = error.getMessage();
            if (message != null && message.contains("private or local")) {
                throw new IllegalArgumentException(message, error);
            }
            throw error;
        }
    }
}
