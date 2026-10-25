package com.mojud.shared.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import com.mojud.shared.error.ApiException;
import com.mojud.shared.error.ErrorCode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The error format through the whole web stack (locale resolution, Bean Validation, message
 * files), using a controller that exists only in this test.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(ApiExceptionHandlerTests.ExampleController.class)
class ApiExceptionHandlerTests {

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @LocalServerPort
    int port;

    @Test
    void validationErrorsListEveryInvalidFieldInEnglishByDefault() throws Exception {
        Problem problem = post("/test/example", "{\"name\":\"\",\"qty\":0,\"note\":\"x\"}", null);

        assertThat(problem.status).isEqualTo(400);
        assertThat(problem.contentType).startsWith("application/problem+json");
        assertThat(problem.code()).isEqualTo("validation.failed");
        assertThat(problem.detail()).isEqualTo("One or more fields are invalid.");
        assertThat(problem.fieldMessage("name")).isEqualTo("must not be blank");
        assertThat(problem.fieldMessage("qty")).isEqualTo("must be greater than 0");
        assertThat(problem.fieldMessage("note")).isEqualTo("must have between 2 and 5 characters or items");
    }

    @Test
    void banglaIsUsedWhenTheClientAsksForIt() throws Exception {
        Problem problem = post("/test/example", "{\"name\":\"\",\"qty\":0,\"note\":\"x\"}", "bn-BD,bn;q=0.9,en;q=0.5");

        assertThat(problem.code()).isEqualTo("validation.failed");
        assertThat(problem.detail()).isEqualTo("এক বা একাধিক ঘর সঠিকভাবে পূরণ করা হয়নি।");
        assertThat(problem.fieldMessage("name")).isEqualTo("খালি রাখা যাবে না");
        assertThat(problem.fieldMessage("note")).isEqualTo("2 থেকে 5টি অক্ষর বা আইটেম হতে হবে");
    }

    @Test
    void anUnsupportedLanguageFallsBackToEnglish() throws Exception {
        assertThat(get("/test/missing", "fr").detail()).isEqualTo("Not found.");
    }

    @Test
    void aBodyFieldOfTheWrongTypeNamesItsPath() throws Exception {
        Problem problem = post("/test/lines", "{\"lines\":[{\"colour\":\"RED\"},{\"colour\":\"PURPLE\"}]}", null);

        assertThat(problem.status).isEqualTo(400);
        assertThat(problem.code()).isEqualTo("validation.failed");
        assertThat(problem.fieldMessage("lines[1].colour")).isEqualTo("has an invalid value");
    }

    @Test
    void malformedJsonIsAMalformedRequest() throws Exception {
        Problem problem = post("/test/lines", "{\"lines\":[", null);

        assertThat(problem.status).isEqualTo(400);
        assertThat(problem.code()).isEqualTo("request.malformed");
    }

    @Test
    void queryParameterConstraintsAreFieldErrors() throws Exception {
        Problem problem = get("/test/page?limit=500", null);

        assertThat(problem.status).isEqualTo(400);
        assertThat(problem.fieldMessage("limit")).isEqualTo("must be at most 100");
    }

    @Test
    void aMalformedIdIsAFieldError() throws Exception {
        Problem problem = get("/test/things/not-a-uuid", null);

        assertThat(problem.status).isEqualTo(400);
        assertThat(problem.fieldMessage("id")).isEqualTo("has an invalid value");
    }

    @Test
    void businessErrorsUseTheirCodeAndKeepTheirDetails() throws Exception {
        Problem problem = get("/test/missing", null);

        assertThat(problem.status).isEqualTo(404);
        assertThat(problem.code()).isEqualTo("resource.not_found");
        assertThat(problem.json.<String>read("$.resource")).isEqualTo("product");
    }

    @Test
    void businessFieldErrorsAreTranslated() throws Exception {
        Problem problem = get("/test/business-field", "bn");

        assertThat(problem.status).isEqualTo(400);
        assertThat(problem.fieldMessage("sku")).isEqualTo("মান সঠিক নয়");
    }

    @Test
    void optimisticLockConflictsAre409() throws Exception {
        Problem problem = get("/test/conflict", null);

        assertThat(problem.status).isEqualTo(409);
        assertThat(problem.code()).isEqualTo("resource.modified");
    }

    @Test
    void unexpectedErrorsRevealNothing() throws Exception {
        Problem problem = get("/test/boom", null);

        assertThat(problem.status).isEqualTo(500);
        assertThat(problem.code()).isEqualTo("server.error");
        assertThat(problem.body).doesNotContain("secret internal state").doesNotContain("IllegalStateException");
    }

    @Test
    void frameworkErrorsGetCodesToo() throws Exception {
        Problem unknownRoute = get("/test/no-such-route", null);
        assertThat(unknownRoute.status).isEqualTo(404);
        assertThat(unknownRoute.code()).isEqualTo("route.not_found");

        Problem wrongMethod = post("/test/missing", "{}", null);
        assertThat(wrongMethod.status).isEqualTo(405);
        assertThat(wrongMethod.code()).isEqualTo("method.not_allowed");
    }

    private Problem get(String path, String language) throws IOException, InterruptedException {
        return send(request(path, language).GET());
    }

    private Problem post(String path, String json, String language) throws IOException, InterruptedException {
        return send(request(path, language)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json)));
    }

    private HttpRequest.Builder request(String path, String language) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        return language == null ? builder : builder.header("Accept-Language", language);
    }

    private static Problem send(HttpRequest.Builder request) throws IOException, InterruptedException {
        HttpResponse<String> response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
        return new Problem(response.statusCode(), response.headers().firstValue("Content-Type").orElse(""),
                response.body(), JsonPath.parse(response.body()));
    }

    private record Problem(int status, String contentType, String body, DocumentContext json) {

        String code() {
            return json.read("$.code");
        }

        String detail() {
            return json.read("$.detail");
        }

        String fieldMessage(String field) {
            List<String> messages = json.read("$.errors[?(@.field == '" + field + "')].message");
            assertThat(messages).as("errors for field %s in %s", field, body).hasSize(1);
            return messages.get(0);
        }
    }

    @RestController
    static class ExampleController {

        record Example(@NotBlank String name, @Positive int qty, @Size(min = 2, max = 5) String note) {
        }

        enum Colour { RED, GREEN }

        record Line(Colour colour) {
        }

        record Lines(List<Line> lines) {
        }

        @PostMapping("/test/example")
        Example create(@Valid @RequestBody Example example) {
            return example;
        }

        @PostMapping("/test/lines")
        Lines lines(@RequestBody Lines lines) {
            return lines;
        }

        @GetMapping("/test/page")
        int page(@RequestParam @Max(100) int limit) {
            return limit;
        }

        @GetMapping("/test/things/{id}")
        UUID thing(@PathVariable UUID id) {
            return id;
        }

        @GetMapping("/test/missing")
        void missing() {
            throw ApiException.notFound().withDetail("resource", "product");
        }

        @GetMapping("/test/business-field")
        void businessField() {
            throw ApiException.invalidField("sku", "field.invalid_value");
        }

        @GetMapping("/test/conflict")
        void conflict() {
            throw new OptimisticLockingFailureException("Product was changed");
        }

        @GetMapping("/test/boom")
        void boom() {
            throw new IllegalStateException("secret internal state");
        }
    }
}
