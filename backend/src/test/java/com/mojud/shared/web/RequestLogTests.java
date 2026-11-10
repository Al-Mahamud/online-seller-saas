package com.mojud.shared.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.mojud.support.ApiClient;
import com.mojud.support.ApiResponse;
import com.mojud.support.CapturedMessages;
import com.mojud.support.IntegrationTest;
import com.mojud.support.TestAccounts;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/** The request log's lines (docs/logging.md). */
@IntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class RequestLogTests {

    @LocalServerPort
    int port;

    @Autowired
    CapturedMessages messages;

    @Test
    void eachCallIsLoggedWithItsIdItsUserAndItsBodiesWithoutSecrets(CapturedOutput output) {
        ApiClient client = new ApiClient(port);
        TestAccounts.Account account = TestAccounts.signUp(client, messages);

        ApiResponse me = client.get("/api/v1/me").expectStatus(200);
        String requestId = me.headers().firstValue(RequestIdFilter.HEADER).orElseThrow();
        String userId = me.json("$.id");

        List<String> lines = output.getAll().lines().filter(line -> line.contains("T[" + requestId + "]")).toList();
        assertThat(lines).anyMatch(line -> line.matches(
                "\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3}\\..+> INFO  - T\\[" + requestId + "] U\\[] - \\[CLIENT-REQ] GET /api/v1/me"));
        assertThat(lines).anyMatch(line -> line.contains("INFO  - T[" + requestId + "] U[" + userId + "] - [CLIENT-RES] GET /api/v1/me 200 ")
                && line.contains("ms\t{") && line.contains(account.userId()));

        List<String> signUp = output.getAll().lines().filter(line -> line.contains("[CLIENT-REQ] POST /api/v1/auth/signup")).toList();
        assertThat(signUp).singleElement().satisfies(line -> assertThat(line)
                .contains("\"password\":\"***\"", "\"code\":\"***\"", account.userId())
                .doesNotContain(account.password()));
    }

    @Test
    void theHealthCheckIsNotLogged(CapturedOutput output) {
        new ApiClient(port).get("/healthz");

        assertThat(output.getAll()).doesNotContain("/healthz");
    }
}
