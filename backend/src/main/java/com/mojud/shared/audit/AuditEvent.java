package com.mojud.shared.audit;

import java.util.Map;
import java.util.UUID;

/**
 * Something that happened and belongs in the business's audit log. Published by {@link AuditTrail};
 * the audit module writes it in the same transaction, so an action and its entry stand or fall
 * together.
 *
 * @param action a dotted name such as {@code staff.role_changed}
 * @param details what changed, for example {@code {"from": "STAFF", "to": "MANAGER"}}; JSON-friendly values only
 */
public record AuditEvent(UUID businessId, UUID actorId, String action, String entityType, UUID entityId,
        Map<String, Object> details) {

    public AuditEvent {
        details = Map.copyOf(details);
    }
}
