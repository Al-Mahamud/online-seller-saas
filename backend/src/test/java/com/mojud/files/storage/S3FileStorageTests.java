package com.mojud.files.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentSkipListMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * The S3 storage against a small S3 server in the test: the requests the SDK really sends
 * (path-style, signed), answered as S3 and R2 answer them. Proves the wiring; R2 itself is
 * checked on a server.
 */
class S3FileStorageTests {

    HttpServer server;
    final Map<String, byte[]> objects = new ConcurrentSkipListMap<>();
    final List<String> requests = new ArrayList<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void storesLoadsAndDeletesInTheBucket() {
        S3FileStorage storage = new S3FileStorage(settings());

        storage.store("a1/f1/large.webp", new byte[] {1, 2, 3});

        assertThat(objects).containsOnlyKeys("mojud-files/a1/f1/large.webp");
        assertThat(storage.load("a1/f1/large.webp")).hasValueSatisfying(bytes -> assertThat(bytes).containsExactly(1, 2, 3));
        assertThat(storage.load("a1/f1/small.webp")).isEmpty();
        assertThat(requests).allMatch(line -> line.contains("AWS4-HMAC-SHA256 Credential=key-id/"), "signed");
        storage.delete("a1/f1/large.webp");
        assertThat(objects).isEmpty();
    }

    @Test
    void deletingAPrefixRemovesOnlyThatBusinessesFiles() {
        S3FileStorage storage = new S3FileStorage(settings());
        storage.store("b1/f1/large.webp", new byte[] {1});
        storage.store("b1/f2/small.png", new byte[] {2});
        storage.store("b10/f3/large.webp", new byte[] {3});

        storage.deleteAll("b1/");

        assertThat(objects).containsOnlyKeys("mojud-files/b10/f3/large.webp");
        assertThatThrownBy(() -> storage.deleteAll("b1")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.store("../x.png", new byte[0])).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void missingSettingsStopTheAppFromStarting() {
        assertThatThrownBy(() -> new S3FileStorage(new MockEnvironment())).hasMessageContaining("mojud.storage.s3.bucket");
    }

    private MockEnvironment settings() {
        return new MockEnvironment()
                .withProperty("mojud.storage.s3.endpoint", "http://127.0.0.1:" + server.getAddress().getPort())
                .withProperty("mojud.storage.s3.bucket", "mojud-files")
                .withProperty("mojud.storage.s3.access-key-id", "key-id")
                .withProperty("mojud.storage.s3.secret-access-key", "secret");
    }

    private void handle(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        String path = URLDecoder.decode(exchange.getRequestURI().getRawPath(), StandardCharsets.UTF_8).substring(1);
        requests.add(method + " " + path + " " + exchange.getRequestHeaders().getFirst("Authorization"));
        byte[] body = exchange.getRequestBody().readAllBytes();
        String sha = exchange.getRequestHeaders().getFirst("x-amz-content-sha256");
        if (sha != null && sha.startsWith("STREAMING-")) {
            body = unchunk(body);
        }
        Map<String, String> query = query(exchange.getRequestURI().getRawQuery());
        switch (method) {
            case "PUT" -> {
                objects.put(path, body);
                exchange.getResponseHeaders().add("ETag", "\"etag\"");
                respond(exchange, 200, new byte[0]);
            }
            case "GET" -> {
                if (query.containsKey("list-type")) {
                    String bucket = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
                    StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?><ListBucketResult>"
                            + "<Name>" + bucket + "</Name><IsTruncated>false</IsTruncated>");
                    String prefix = bucket + "/" + query.getOrDefault("prefix", "");
                    for (String key : objects.keySet()) {
                        if (key.startsWith(prefix)) {
                            xml.append("<Contents><Key>").append(key.substring(bucket.length() + 1)).append("</Key><Size>")
                                    .append(objects.get(key).length).append("</Size></Contents>");
                        }
                    }
                    exchange.getResponseHeaders().add("Content-Type", "application/xml");
                    respond(exchange, 200, xml.append("</ListBucketResult>").toString().getBytes(StandardCharsets.UTF_8));
                } else if (objects.containsKey(path)) {
                    respond(exchange, 200, objects.get(path));
                } else {
                    exchange.getResponseHeaders().add("Content-Type", "application/xml");
                    respond(exchange, 404, "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Error><Code>NoSuchKey</Code>"
                            .concat("<Message>The specified key does not exist.</Message></Error>").getBytes(StandardCharsets.UTF_8));
                }
            }
            case "DELETE" -> {
                objects.remove(path);
                respond(exchange, 204, null);
            }
            default -> respond(exchange, 405, new byte[0]);
        }
    }

    /** aws-chunked: "<hex size>;chunk-signature=...\r\n<bytes>\r\n", ending with a size of 0. */
    private static byte[] unchunk(byte[] body) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        int at = 0;
        while (true) {
            int lineEnd = indexOfCrLf(body, at);
            String header = new String(body, at, lineEnd - at, StandardCharsets.US_ASCII);
            int size = Integer.parseInt(header.split(";")[0], 16);
            if (size == 0) {
                return out.toByteArray();
            }
            out.write(body, lineEnd + 2, size);
            at = lineEnd + 2 + size + 2;
        }
    }

    private static int indexOfCrLf(byte[] bytes, int from) {
        for (int i = from; i < bytes.length - 1; i++) {
            if (bytes[i] == '\r' && bytes[i + 1] == '\n') {
                return i;
            }
        }
        throw new IllegalArgumentException("No line end");
    }

    private static Map<String, String> query(String raw) {
        Map<String, String> values = new TreeMap<>();
        if (raw != null) {
            for (String pair : raw.split("&")) {
                String[] parts = pair.split("=", 2);
                values.put(URLDecoder.decode(parts[0], StandardCharsets.UTF_8),
                        parts.length > 1 ? URLDecoder.decode(parts[1], StandardCharsets.UTF_8) : "");
            }
        }
        return values;
    }

    private static void respond(HttpExchange exchange, int status, byte[] body) throws IOException {
        exchange.sendResponseHeaders(status, body == null || body.length == 0 ? -1 : body.length);
        if (body != null && body.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        }
        exchange.close();
    }
}
