package com.mojud;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mojud.shared.db.system.SystemDatabase;
import com.mojud.support.CapturedMessages;
import com.mojud.support.CrossTenant;
import com.mojud.support.CrossTenant.Tenant;
import com.mojud.support.IntegrationTest;
import com.mojud.tenancy.BusinessRole;
import com.mojud.tenancy.RequiresRole;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * The cross-tenant suite must catch a real leak. This test adds an endpoint that reads memberships
 * through the system role, ignoring the business, and checks that the suite's tools notice both
 * that it has no case and that it answers with another business's data.
 */
@IntegrationTest
@Import(CrossTenantSuiteSelfTest.LeakyController.class)
class CrossTenantSuiteSelfTest {

    @RestController
    static class LeakyController {

        private final SystemDatabase database;

        LeakyController(SystemDatabase database) {
            this.database = database;
        }

        /** Deliberately wrong: finds the membership in any business. */
        @GetMapping("/api/v1/test-leak/memberships/{membershipId}")
        @RequiresRole(BusinessRole.STAFF)
        Map<String, Object> leak(@PathVariable UUID membershipId) {
            return database.jdbc().sql("select business_id, user_id, role from memberships where id = ?")
                    .param(membershipId).query().singleRow();
        }
    }

    @LocalServerPort
    int port;

    @Autowired
    CapturedMessages messages;

    @Autowired
    SystemDatabase systemDatabase;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping mapping;

    @Test
    void anEndpointWithoutACaseIsReported() {
        Set<String> endpoints = CrossTenant.endpoints(mapping);

        assertThatThrownBy(() -> CrossTenant.assertEveryEndpointHasACase(endpoints, Set.of()))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("GET /api/v1/test-leak/memberships/{membershipId}");
    }

    @Test
    void aLeakingEndpointIsCaught() {
        Tenant a = CrossTenant.setUp(port, messages, this::membershipId);
        Tenant b = CrossTenant.setUp(port, messages, this::membershipId);

        assertThatThrownBy(() -> CrossTenant.expectNotFound(
                b.owner().get("/api/v1/test-leak/memberships/" + a.membershipIdOfStaff())))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("expected 404");
        assertThatThrownBy(() -> CrossTenant.expectNoLeak(
                b.owner().get("/api/v1/test-leak/memberships/" + a.membershipIdOfStaff()), a))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("leaks another business's data");
    }

    private UUID membershipId(UUID businessId, UUID userId) {
        return systemDatabase.jdbc().sql("select id from memberships where business_id = ? and user_id = ?")
                .params(businessId, userId).query(UUID.class).single();
    }
}
