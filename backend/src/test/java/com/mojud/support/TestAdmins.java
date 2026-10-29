package com.mojud.support;

import com.mojud.shared.db.system.SystemDatabase;
import java.time.Clock;
import java.util.Map;

/** A platform admin, signed in with two-step sign-in, as admins must be (implementation-plan.md 13a). */
public final class TestAdmins {

    private TestAdmins() {
    }

    /** {@code clock}: the server's clock (TestClock), which the codes follow. */
    public static ApiClient signIn(int port, CapturedMessages messages, SystemDatabase systemDatabase, Clock clock) {
        ApiClient setUp = new ApiClient(port);
        TestAccounts.Account account = TestAccounts.signUp(setUp, messages);
        systemDatabase.jdbc().sql("update users set is_platform_admin = true where email = ?").param(account.email()).update();
        String secret = setUp.post("/api/v1/me/two-factor/setup", Map.of("password", account.password()))
                .expectStatus(200).json("$.secret");
        setUp.post("/api/v1/me/two-factor/enable", Map.of("code", TestTotp.code(secret, clock, 0))).expectStatus(200);
        // Admin rights are read at sign-in; the next step's code, as this one is used up.
        ApiClient admin = new ApiClient(port);
        admin.post("/api/v1/auth/login", Map.of("login", account.email(), "password", account.password())).expectStatus(202);
        admin.post("/api/v1/auth/login/two-factor", Map.of("code", TestTotp.code(secret, clock, 1))).expectStatus(200);
        return admin;
    }
}
