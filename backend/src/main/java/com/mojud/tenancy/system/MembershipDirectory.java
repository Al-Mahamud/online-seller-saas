package com.mojud.tenancy.system;

import com.mojud.shared.db.system.SystemDatabase;
import com.mojud.tenancy.BusinessRole;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Questions about one user across all their businesses, which row-level security cannot answer
 * because it shows one business at a time: which businesses they belong to, and how many they own.
 * Read-only; every change to a membership happens inside that business's TenantScope.
 */
@Component
public class MembershipDirectory {

    public record UserBusiness(UUID businessId, String name, BusinessRole role, Instant lastUsedAt) {
    }

    private final SystemDatabase database;

    MembershipDirectory(SystemDatabase database) {
        this.database = database;
    }

    /** Active memberships in active businesses, most recently used first. */
    public List<UserBusiness> businessesOf(UUID userId) {
        return database.jdbc().sql("""
                        select b.id, b.name, m.role, m.last_used_at
                        from memberships m
                        join businesses b on b.id = m.business_id
                        where m.user_id = ? and m.status = 'ACTIVE' and b.status = 'ACTIVE'
                        order by m.last_used_at desc, b.name
                        """)
                .param(userId)
                .query((row, n) -> new UserBusiness(row.getObject(1, UUID.class), row.getString(2),
                        BusinessRole.valueOf(row.getString(3)), row.getTimestamp(4).toInstant()))
                .list();
    }

    /**
     * The business to select when the session has none: the one used last. One indexed row
     * (ix_memberships_user), since it runs on requests until a business is selected.
     */
    public Optional<UUID> lastUsedBusiness(UUID userId) {
        return database.jdbc().sql("""
                        select m.business_id
                        from memberships m
                        join businesses b on b.id = m.business_id
                        where m.user_id = ? and m.status = 'ACTIVE' and b.status = 'ACTIVE'
                        order by m.last_used_at desc
                        limit 1
                        """)
                .param(userId)
                .query(UUID.class)
                .optional();
    }

    public int ownedBusinessCount(UUID userId) {
        return database.jdbc().sql("""
                        select count(*) from memberships
                        where user_id = ? and role = 'OWNER' and status = 'ACTIVE'
                        """)
                .param(userId)
                .query(Integer.class)
                .single();
    }
}
