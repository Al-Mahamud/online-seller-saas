package com.mojud.audit;

import com.mojud.shared.paging.PageQuery;
import com.mojud.shared.paging.PagedResult;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * What platform administrators do (admin_audit). The log spans businesses, so it is written with the
 * system role, on its own connection: callers record an entry right after the action succeeded.
 * Every admin action records one.
 */
public interface AdminAudit {

    /**
     * @param businessId the business the action touched, or null (a plan)
     * @param details key/value pairs, in pairs; null values are left out
     */
    void record(UUID adminId, String action, String targetType, String targetId, UUID businessId, Object... details);

    /** {@code adminName}: null once the person's account is gone; {@code businessName}, once the business is. */
    record Entry(UUID id, UUID adminId, @Nullable String adminName, String action, String targetType, String targetId,
            @Nullable UUID businessId, @Nullable String businessName, Map<String, Object> details, Instant at) {
    }

    /** The latest few, newest first; {@code businessId} null for every entry. */
    List<Entry> recent(UUID businessId, int limit);

    /**
     * A page of them, newest first; {@code businessId} null for every entry, {@code actions} empty
     * for every kind.
     */
    PagedResult<Entry> page(UUID businessId, List<String> actions, PageQuery paging);
}
