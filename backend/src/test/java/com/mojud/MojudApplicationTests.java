package com.mojud;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import com.mojud.support.IntegrationTest;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

@IntegrationTest
class MojudApplicationTests {

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @LocalServerPort
    int port;

    @Autowired
    Flyway flyway;

    @Test
    void appliesEveryMigration() {
        MigrationInfo[] applied = flyway.info().applied();

        assertThat(applied).isNotEmpty();
        assertThat(applied).allSatisfy(migration -> assertThat(migration.getState().isFailed()).isFalse());
        assertThat(flyway.info().pending()).isEmpty();
    }

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
