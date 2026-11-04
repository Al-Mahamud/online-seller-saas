package com.mojud.support;

import com.mojud.shared.tenant.TenantScope;
import com.mojud.tenancy.BusinessRole;
import com.mojud.tenancy.membership.Membership;
import com.mojud.tenancy.membership.MembershipRepository;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.transaction.support.TransactionTemplate;

/** Businesses for integration tests. */
public final class TestBusinesses {

    private TestBusinesses() {
    }

    /**
     * Creates a business through the API, as it starts (on Free); it becomes the client's selected
     * business. For tests of the plans themselves.
     */
    public static UUID createOnFree(ApiClient client, String name) {
        return UUID.fromString(client.post("/api/v1/businesses", Map.of("name", name)).expectStatus(201).json("$.id"));
    }

    /**
     * Creates a business as {@link #createOnFree} does, then puts it on Pro for a year: most tests
     * aren't about plans, and Free's limits (no team, 60 orders a month) would get in their way.
     */
    public static UUID create(ApiClient client, String name) {
        UUID business = createOnFree(client, name);
        client.post("/dev/subscription?businessId=" + business + "&status=ACTIVE&plan=PRO&days=365", null).expectStatus(204);
        return business;
    }

    /**
     * Adds a member directly, as staff invites do not exist yet (Phase 2b). {@code userId} is
     * the {@code id} from {@code GET /api/v1/me}.
     */
    public static void addMember(MembershipRepository memberships, TransactionTemplate transactions,
            UUID businessId, UUID userId, BusinessRole role) {
        TenantScope.run(businessId, () -> transactions.executeWithoutResult(status ->
                memberships.save(new Membership(userId, role, Instant.now()))));
    }

    public static UUID userId(ApiClient client) {
        return UUID.fromString(client.get("/api/v1/me").expectStatus(200).json("$.id"));
    }
}
