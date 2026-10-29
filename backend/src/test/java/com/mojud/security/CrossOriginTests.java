package com.mojud.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.mojud.support.IntegrationTest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Other sites cannot use the API from a visitor's browser (backend-plan.md §11): the web app is
 * served from the API's own address, so no origin is allowed by default, and the browser blocks
 * a page elsewhere from reading answers or sending credentialed requests.
 */
@IntegrationTest
class CrossOriginTests {

    static final String EVIL = "https://evil.example";

    @LocalServerPort
    int port;

    final HttpClient http = HttpClient.newHttpClient();

    @Test
    void aPreflightFromAnotherSiteIsNotAllowed() throws Exception {
        HttpResponse<String> preflight = http.send(HttpRequest.newBuilder(uri("/api/v1/auth/login"))
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                .header("Origin", EVIL)
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "content-type,x-xsrf-token")
                .build(), HttpResponse.BodyHandlers.ofString());

        assertThat(preflight.headers().firstValue("Access-Control-Allow-Origin")).isEmpty();
        assertThat(preflight.headers().firstValue("Access-Control-Allow-Credentials")).isEmpty();
    }

    @Test
    void anAnswerToAnotherSiteCarriesNoPermissionToReadIt() throws Exception {
        HttpResponse<String> answer = http.send(HttpRequest.newBuilder(uri("/api/v1/auth/options")).header("Origin", EVIL).build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(answer.headers().firstValue("Access-Control-Allow-Origin")).isEmpty();
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }
}
