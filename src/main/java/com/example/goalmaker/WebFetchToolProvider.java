package com.example.goalmaker;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class WebFetchToolProvider {
    @Value("${web.fetch.max-attempts:2}") private int maxAttempts = 2;
    @Value("${web.fetch.retry-delay-millis:250}") private long retryDelayMillis = 250;
    @Value("${web.fetch.max-response-bytes:2097152}") private int maxResponseBytes = 2_097_152;
    @Value("${web.fetch.max-redirects:5}") private int maxRedirects = 5;
    @Value("${web.fetch.default-max-chars:12000}") private int defaultMaxChars = 12_000;
    @Value("${web.fetch.allow-private-addresses:false}") private boolean allowPrivateAddresses;

    private final ObjectMapper mapper;
    private final WebHttpClient http;

    public WebFetchToolProvider() {
        this(new ObjectMapper());
    }

    @Autowired
    public WebFetchToolProvider(ObjectMapper mapper) {
        this(mapper, HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(10))
                .build());
    }

    WebFetchToolProvider(ObjectMapper mapper, HttpClient client) {
        this.mapper = mapper;
        this.http = new WebHttpClient(client);
    }

    public List<ToolDefinition> tools() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("url", Map.of("type", "string",
                "description", "Absolute public http(s) URL returned by web_search."));
        properties.put("max_chars", Map.of("type", "integer",
                "description", "Maximum readable characters to return (1000-20000).",
                "minimum", 1_000, "maximum", 20_000));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("url"));
        return List.of(new ToolDefinition(
                "web_fetch",
                "Fetch a public web page, extract its main readable text, and return structured source evidence.",
                schema,
                "builtin:web_fetch",
                true,
                this::fetch));
    }

    private String fetch(Map<String, Object> arguments) throws Exception {
        String requested = arguments.get("url") == null ? "" : String.valueOf(arguments.get("url")).trim();
        if (requested.isBlank()) throw new IllegalArgumentException("url is required");
        int maxChars = integer(arguments.get("max_chars"), defaultMaxChars, 1_000, 20_000);
        URI current = URI.create(requested);
        WebHttpClient.Response response = null;
        for (int redirect = 0; redirect <= Math.max(0, maxRedirects); redirect++) {
            validatePublicHttpUrl(current);
            response = http.get(current, "text/html, text/plain;q=0.9, application/xhtml+xml;q=0.8",
                    Duration.ofSeconds(25), maxAttempts, retryDelayMillis, maxResponseBytes);
            if (!redirect(response.status())) break;
            String location = response.headers().firstValue("Location")
                    .orElseThrow(() -> new IllegalStateException("redirect response had no Location header"));
            current = current.resolve(location);
            if (redirect == maxRedirects) throw new IllegalStateException("too many redirects");
        }
        if (response == null || response.status() / 100 != 2) {
            throw new IllegalStateException("HTTP " + (response == null ? "unknown" : response.status())
                    + " when fetching " + current);
        }

        String contentType = response.headers().firstValue("Content-Type").orElse("text/html")
                .split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        String title = "";
        String text;
        if (contentType.equals("text/html") || contentType.equals("application/xhtml+xml")) {
            Document document = Jsoup.parse(response.body(), current.toString());
            title = document.title().trim();
            document.select("script, style, noscript, svg, nav, footer, header, form, aside").remove();
            Element root = document.selectFirst("article");
            if (root == null) root = document.selectFirst("main, [role=main]");
            if (root == null) root = document.body();
            text = root == null ? "" : root.text().replaceAll("\\s+", " ").trim();
        } else if (contentType.startsWith("text/")) {
            text = response.body().replaceAll("\\s+", " ").trim();
        } else {
            throw new IllegalStateException("unsupported content type " + contentType);
        }
        if (text.isBlank()) throw new IllegalStateException("page contained no readable text");
        boolean truncated = response.truncated() || text.length() > maxChars;
        if (text.length() > maxChars) text = text.substring(0, maxChars);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("requested_url", requested);
        payload.put("url", current.toString());
        payload.put("title", title);
        payload.put("content_type", contentType);
        payload.put("retrieved_at", Instant.now().toString());
        payload.put("truncated", truncated);
        payload.put("content", text);
        return mapper.writeValueAsString(payload);
    }

    private void validatePublicHttpUrl(URI uri) throws Exception {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!("http".equals(scheme) || "https".equals(scheme)) || uri.getHost() == null) {
            throw new IllegalArgumentException("url must be an absolute http(s) URL");
        }
        if (uri.getUserInfo() != null) throw new IllegalArgumentException("url must not contain credentials");
        if (allowPrivateAddresses) return;
        for (InetAddress address : InetAddress.getAllByName(uri.getHost())) {
            if (privateAddress(address)) {
                throw new IllegalArgumentException("url resolves to a private or local network address");
            }
        }
    }

    private static boolean privateAddress(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) return true;
        if (address instanceof Inet6Address) {
            byte[] bytes = address.getAddress();
            return bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc;
        }
        byte[] bytes = address.getAddress();
        int first = Byte.toUnsignedInt(bytes[0]);
        int second = bytes.length > 1 ? Byte.toUnsignedInt(bytes[1]) : 0;
        return first == 0 || first == 10 || first == 127 || first >= 224
                || (first == 100 && second >= 64 && second <= 127)
                || (first == 169 && second == 254)
                || (first == 172 && second >= 16 && second <= 31)
                || (first == 192 && second == 168);
    }

    private static boolean redirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    private static int integer(Object value, int fallback, int minimum, int maximum) {
        int parsed;
        try {
            parsed = value == null ? fallback : Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("max_chars is invalid");
        }
        if (parsed < minimum || parsed > maximum) {
            throw new IllegalArgumentException("max_chars must be between " + minimum + " and " + maximum);
        }
        return parsed;
    }
}
