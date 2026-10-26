package com.mojud.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Talks to the running application the way the web app will: keeps cookies, fetches the CSRF
 * cookie on first need, and sends it back in the {@code X-XSRF-TOKEN} header. One instance is one
 * browser.
 */
public final class ApiClient {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final String baseUrl;
    private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
    private final HttpClient http;
    private final Map<String, String> headers = new LinkedHashMap<>();

    public ApiClient(int port) {
        this.baseUrl = "http://localhost:" + port;
        this.http = HttpClient.newBuilder().cookieHandler(cookies).build();
    }

    /** Sends this header with every request from now on, e.g. {@code Accept-Language}. */
    public ApiClient withHeader(String name, String value) {
        headers.put(name, value);
        return this;
    }

    public ApiResponse get(String path) {
        return send("GET", path, null, false);
    }

    public ApiResponse post(String path, Object body) {
        return send("POST", path, body, true);
    }

    public ApiResponse put(String path, Object body) {
        return send("PUT", path, body, true);
    }

    public ApiResponse patch(String path, Object body) {
        return send("PATCH", path, body, true);
    }

    public ApiResponse delete(String path) {
        return send("DELETE", path, null, true);
    }

    /** Like {@link #post} but without the CSRF header, to prove CSRF protection works. */
    public ApiResponse postWithoutCsrf(String path, Object body) {
        return send("POST", path, body, false);
    }

    /** A multipart form upload with one file part named {@code file}. */
    public ApiResponse upload(String path, String fileName, byte[] content) {
        String boundary = "----test-" + java.util.UUID.randomUUID();
        java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();
        body.writeBytes(("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"" + fileName + "\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        body.writeBytes(content);
        body.writeBytes(("\r\n--" + boundary + "--\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header("X-XSRF-TOKEN", csrfToken());
        headers.forEach(request::header);
        try {
            HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            return new ApiResponse(response.statusCode(), response.body(), response.headers());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** A GET whose body is binary, such as an image. */
    public HttpResponse<byte[]> download(String path) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl + path));
        headers.forEach(request::header);
        try {
            return http.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    public Optional<String> cookie(String name) {
        return cookies.getCookieStore().getCookies().stream()
                .filter(cookie -> cookie.getName().equals(name))
                .map(HttpCookie::getValue)
                .findFirst();
    }

    private ApiResponse send(String method, String path, Object body, boolean withCsrf) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .method(method, body == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
                .header("Content-Type", "application/json");
        if (withCsrf) {
            request.header("X-XSRF-TOKEN", csrfToken());
        }
        headers.forEach(request::header);
        try {
            HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            return new ApiResponse(response.statusCode(), response.body(), response.headers());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private String csrfToken() {
        return cookie("XSRF-TOKEN").orElseGet(() -> {
            get("/api/v1/auth/csrf").expectStatus(204);
            return cookie("XSRF-TOKEN").orElseThrow(() -> new AssertionError("No XSRF-TOKEN cookie was set"));
        });
    }
}
