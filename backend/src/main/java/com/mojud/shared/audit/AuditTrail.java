package com.mojud.shared.audit;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * How modules write to the audit log: call {@link #record} inside the transaction that makes the
 * change. Modules need not know the audit module; it listens for {@link AuditEvent}s.
 */
@Component
public class AuditTrail {

    private final ApplicationEventPublisher events;

    AuditTrail(ApplicationEventPublisher events) {
        this.events = events;
    }

    /**
     * @param actorId who did it; null for the system itself
     * @param details key/value pairs, in pairs: {@code "from", oldRole, "to", newRole}; null values are left out
     * @throws IllegalStateException outside a transaction, where the entry could outlive a failed change
     */
    public void record(UUID businessId, UUID actorId, String action, String entityType, UUID entityId,
            Object... details) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Audit entries are written in the transaction of the change");
        }
        events.publishEvent(new AuditEvent(businessId, actorId, action, entityType, entityId, pairs(details)));
    }

    private static Map<String, Object> pairs(Object[] details) {
        if (details.length % 2 != 0) {
            throw new IllegalArgumentException("details come in key/value pairs");
        }
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < details.length; i += 2) {
            if (details[i + 1] != null) {
                Object value = details[i + 1];
                map.put((String) details[i], value instanceof Enum<?> e ? e.name() : value instanceof UUID ? value.toString() : value);
            }
        }
        return map;
    }
}
