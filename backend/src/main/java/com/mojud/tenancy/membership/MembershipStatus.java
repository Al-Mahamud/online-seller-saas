package com.mojud.tenancy.membership;

public enum MembershipStatus {
    ACTIVE,
    /** No access any more; the row stays for the audit trail (Phase 2b). */
    REMOVED
}
