package com.mojud.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.mojud.support.IntegrationTest;
import com.mojud.tenancy.RequiresRole;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Every endpoint says who may call it (security review, implementation-plan.md 13c). A method
 * without {@link RequiresRole} runs for any signed-in user, so each one must be open on purpose
 * (sign-in, public links), the platform admin's (guarded by SecurityConfig), or about the user's
 * own account, listed here by name. Anything else must name the lowest business role it needs.
 */
@IntegrationTest
class EndpointAccessTests {

    /** Signed-in user, no business needed. Adding one here is a security decision: say why. */
    static final Set<String> ACCOUNT_LEVEL = Set.of(
            // The user's own account.
            "GET /api/v1/me", "PATCH /api/v1/me", "POST /api/v1/me/password",
            "POST /api/v1/me/contact/code", "POST /api/v1/me/contact",
            "GET /api/v1/me/two-factor", "POST /api/v1/me/two-factor/setup", "POST /api/v1/me/two-factor/enable",
            "POST /api/v1/me/two-factor/recovery-codes", "POST /api/v1/me/two-factor/disable",
            // Their businesses: list, create one, pick one (membership checked inside), join by invite.
            "GET /api/v1/businesses", "POST /api/v1/businesses", "POST /api/v1/me/switch-business",
            "POST /api/v1/invites/accept",
            // The same for everyone: Bangladesh's divisions, districts and areas; the plans and prices.
            "GET /api/v1/geo/divisions", "GET /api/v1/geo/districts", "GET /api/v1/geo/districts/{id}/areas",
            "GET /api/v1/plans",
            // API documentation: off on servers (API_DOCS_ENABLED), on in tests.
            "GET /api/v1/openapi", "GET /api/v1/openapi.yaml", "GET /api/v1/openapi/swagger-config");

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping mappings;

    @Test
    void everyBusinessEndpointNamesTheRoleItNeeds() {
        Set<String> withoutRole = new TreeSet<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : mappings.getHandlerMethods().entrySet()) {
            HandlerMethod handler = entry.getValue();
            boolean hasRole = AnnotatedElementUtils.hasAnnotation(handler.getMethod(), RequiresRole.class)
                    || AnnotatedElementUtils.hasAnnotation(handler.getBeanType(), RequiresRole.class);
            for (String path : entry.getKey().getPatternValues()) {
                if (!path.startsWith("/api/v1/") || hasRole || path.startsWith("/api/v1/auth/")
                        || path.startsWith("/api/v1/public/") || path.startsWith("/api/v1/admin/")) {
                    continue;
                }
                for (var method : entry.getKey().getMethodsCondition().getMethods()) {
                    String endpoint = method + " " + path;
                    if (!ACCOUNT_LEVEL.contains(endpoint)) {
                        withoutRole.add(endpoint);
                    }
                }
            }
        }
        assertThat(withoutRole).as("endpoints without @RequiresRole that are not listed as account-level").isEmpty();
    }
}
