package com.mojud;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class MojudApplicationTests {

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @LocalServerPort
    int port;

    @Test
    void reportsHealthyAtHealthz() throws Exception {
        HttpResponse<String> response = get("/healthz");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"status\":\"UP\"");
    }

    @Test
    void exposesNoOtherActuatorEndpoint() throws Exception {
        assertThat(get("/env").statusCode()).isEqualTo(404);
        assertThat(get("/actuator/health").statusCode()).isEqualTo(404);
    }

    @Test
    void servesOpenApiDocument() throws Exception {
        HttpResponse<String> response = get("/api/v1/openapi");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"openapi\"").contains("Mojud API");
    }

    private HttpResponse<String> get(String path) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
