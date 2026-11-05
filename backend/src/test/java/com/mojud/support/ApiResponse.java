package com.mojud.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import java.net.http.HttpHeaders;
import java.util.List;

public record ApiResponse(int status, String body, HttpHeaders headers) {

    public ApiResponse expectStatus(int expected) {
        assertThat(status).as("HTTP status; body: %s", body).isEqualTo(expected);
        return this;
    }

    /** Asserts a problem response with this status and error code. */
    public ApiResponse expectError(int expectedStatus, String expectedCode) {
        expectStatus(expectedStatus);
        assertThat(headers.firstValue("Content-Type")).hasValueSatisfying(
                type -> assertThat(type).startsWith("application/problem+json"));
        assertThat(this.<String>json("$.code")).as("error code; body: %s", body).isEqualTo(expectedCode);
        return this;
    }

    /**
     * The body without the request's ID, which differs on every request: for checking that two
     * answers say exactly the same thing, such as for a real and an unknown account.
     */
    public String bodyWithoutRequestId() {
        return body.replaceAll(",?\"requestId\":\"[^\"]*\"", "");
    }

    public <T> T json(String path) {
        return JsonPath.read(body, path);
    }

    public List<String> setCookies() {
        return headers.allValues("Set-Cookie");
    }
}
