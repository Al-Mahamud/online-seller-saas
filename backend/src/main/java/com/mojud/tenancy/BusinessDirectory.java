package com.mojud.tenancy;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Businesses across the platform, for platform administration (the system role reads them). */
public interface BusinessDirectory {

    record Owner(UUID id, String name, @Nullable String phone, @Nullable String email) {
    }

    /** {@code members}: active members, the owner included. */
    record BusinessSummary(UUID id, String name, @Nullable String phone, @Nullable String address, String status,
            Instant createdAt, @Nullable Owner owner, int members) {
    }

    /**
     * Newest first, up to {@code limit}. {@code q}: part of the name, or an owner's phone or email
     * (written any way); blank lists them all.
     */
    List<BusinessSummary> search(String q, int limit);

    Optional<BusinessSummary> find(UUID businessId);

    long businesses();

    long createdSince(Instant since);
}
