package com.mojud.tenancy;

import java.util.List;
import java.util.UUID;

/** Who works in the business being worked in (TenantScope), for reaching them: emails, for one. */
public interface BusinessMembers {

    /** Active members with {@code role} or a higher one. */
    List<UUID> withRoleAtLeast(BusinessRole role);
}
