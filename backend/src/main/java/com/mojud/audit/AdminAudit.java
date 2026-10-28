package com.mojud.audit;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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

    record Entry(UUID id, UUID adminId, String action, String targetType, String targetId, UUID businessId,
            Map<String, Object> details, Instant at) {
    }

    /** Newest first; {@code businessId} null for every entry. */
    List<Entry> recent(UUID businessId, int limit);
}
