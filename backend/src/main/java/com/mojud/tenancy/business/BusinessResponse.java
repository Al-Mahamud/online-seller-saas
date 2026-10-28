package com.mojud.tenancy.business;

import com.mojud.shared.files.FileLinks;
import com.mojud.shared.phone.PhoneNumber;
import com.mojud.tenancy.BusinessRole;
import java.time.Instant;
import java.util.UUID;

/** A business, with the caller's role in it. */
record BusinessResponse(UUID id, String name, String phone, String address, FileLinks.Links logo, BusinessRole role,
        Instant deletionDueAt) {

    static BusinessResponse of(Business business, BusinessRole role) {
        return new BusinessResponse(business.getId(), business.getName(),
                business.getPhone().map(PhoneNumber::e164).orElse(null), business.getAddress(),
                FileLinks.of(business.getLogoFileId()), role, business.getDeletionDueAt());
    }
}
