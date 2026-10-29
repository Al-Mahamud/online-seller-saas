package com.mojud.shared.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.mojud.support.IntegrationTest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Keeps the frontend's copy of the API description current: the web app's typed client is
 * generated from {@code frontend/src/api/openapi.json} (frontend-plan.md, F0). When the API
 * changes, this test rewrites that file and fails; then regenerate the client
 * ({@code npm run api:types} in frontend/) and commit both. {@code -DupdateSnapshots=true}
 * rewrites it without failing.
 */
@IntegrationTest
class OpenApiSnapshotTests {

    static final Path SNAPSHOT = Path.of("../frontend/src/api/openapi.json");

    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();

    @LocalServerPort
    int port;

    @Test
    void frontendHasTheCurrentApiDescription() throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/openapi")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("GET /api/v1/openapi (see the log for the cause)").isEqualTo(200);

        Map<String, Object> description = JSON.readValue(response.body(), new TypeReference<Map<String, Object>>() {
        });
        // The test server's random port; the web app calls the API on its own origin.
        description.remove("servers");
        String current = JSON.writeValueAsString(description) + "\n";

        String committed = Files.exists(SNAPSHOT) ? Files.readString(SNAPSHOT, StandardCharsets.UTF_8) : "";
        if (!current.equals(committed)) {
            Files.writeString(SNAPSHOT, current, StandardCharsets.UTF_8);
            if (!Boolean.getBoolean("updateSnapshots")) {
                throw new AssertionError(SNAPSHOT.normalize() + " was out of date and has been rewritten: "
                        + "run `npm run api:types` in frontend/, then commit both files");
            }
        }
    }
}
