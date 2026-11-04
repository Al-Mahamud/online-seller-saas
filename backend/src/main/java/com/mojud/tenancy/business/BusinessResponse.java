package com.mojud.tenancy.business;

import com.mojud.tenancy.Permission;
import java.util.List;
import java.util.Set;
import com.mojud.shared.files.FileLinks;
import com.mojud.shared.phone.PhoneNumber;
import com.mojud.tenancy.BusinessRole;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** A business, with the caller's role in it and what that role may do here. */
record BusinessResponse(UUID id, String name, @Nullable String phone, @Nullable String address,
        FileLinks.@Nullable Links logo, BusinessRole role, List<Permission> permissions, @Nullable Instant deletionDueAt) {

    static BusinessResponse of(Business business, BusinessRole role, Set<Permission> permissions) {
        return new BusinessResponse(business.getId(), business.getName(),
                business.getPhone().map(PhoneNumber::e164).orElse(null), business.getAddress(),
                FileLinks.of(business.getLogoFileId()), role, permissions.stream().sorted().toList(),
                business.getDeletionDueAt());
    }
}
