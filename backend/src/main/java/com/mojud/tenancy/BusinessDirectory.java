package com.mojud.tenancy;

import com.mojud.shared.paging.PageQuery;
import com.mojud.shared.paging.PagedResult;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Businesses across the platform, for platform administration (the system role reads them). */
public interface BusinessDirectory {

    /** {@code userId}: what the owner signs in with. */
    record Owner(UUID id, String name, @Nullable String phone, @Nullable String email, String userId) {
    }

    /** {@code members}: active members, the owner included. */
    record BusinessSummary(UUID id, String name, @Nullable String phone, @Nullable String address, String status,
            Instant createdAt, @Nullable Owner owner, int members) {
    }

    /**
     * Newest first, up to {@code limit}. {@code q}: part of the name, or an owner's phone or email
     * (written any way); blank lists them all.
     */
    PagedResult<BusinessSummary> search(String q, Narrowing narrowing, PageQuery paging);

    /** Only businesses on this plan ({@code planCode}) or in this subscription state; null for any. */
    record Narrowing(@Nullable String planCode, @Nullable String state) {

        public static final Narrowing NONE = new Narrowing(null, null);
    }

    Optional<BusinessSummary> find(UUID businessId);

    long businesses();

    long createdSince(Instant since);
}
