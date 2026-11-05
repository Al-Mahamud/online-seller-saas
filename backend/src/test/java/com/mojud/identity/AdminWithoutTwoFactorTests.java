package com.mojud.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.mojud.shared.db.system.SystemDatabase;
import com.mojud.support.ApiClient;
import com.mojud.support.CapturedMessages;
import com.mojud.support.IntegrationTest;
import com.mojud.support.TestAccounts;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;

/** The local runs' switch: admin rights without two-step sign-in (a server refuses it, ProductionChecks). */
@IntegrationTest
@TestPropertySource(properties = "mojud.admin.require-two-factor=false")
class AdminWithoutTwoFactorTests {

    @LocalServerPort
    int port;

    @Autowired
    CapturedMessages messages;

    @Autowired
    SystemDatabase systemDatabase;

    @Test
    void anAdminSignedInWithAPasswordOnlyReachesTheAdminArea() {
        TestAccounts.Account account = TestAccounts.signUp(new ApiClient(port), messages);
        systemDatabase.jdbc().sql("update users set is_platform_admin = true where email = ?").param(account.email()).update();
        ApiClient admin = new ApiClient(port);
        admin.post("/api/v1/auth/login", Map.of("userId", account.userId(), "password", account.password())).expectStatus(200);

        assertThat(admin.get("/api/v1/me").<Boolean>json("$.twoFactorEnabled")).isFalse();
        admin.get("/api/v1/admin/metrics").expectStatus(200);
        // Everyone else is refused as before.
        ApiClient owner = new ApiClient(port);
        TestAccounts.signUp(owner, messages);
        owner.get("/api/v1/admin/metrics").expectError(403, "access.denied");
    }
}
