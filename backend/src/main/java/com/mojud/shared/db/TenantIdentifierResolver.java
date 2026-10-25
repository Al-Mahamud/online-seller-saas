package com.mojud.shared.db;

import com.mojud.shared.tenant.TenantScope;
import java.util.Map;
import java.util.UUID;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.context.spi.CurrentTenantIdentifierResolver;
import org.springframework.boot.hibernate.autoconfigure.HibernatePropertiesCustomizer;
import org.springframework.stereotype.Component;

/**
 * Layer 1 of tenant isolation (backend-plan.md §4): Hibernate adds {@code business_id = <current>}
 * to every query on an entity with a {@code @TenantId} field and fills that field on insert. Outside
 * a {@link TenantScope} the tenant is a nil UUID no business has, so such queries find nothing.
 */
@Component
class TenantIdentifierResolver implements CurrentTenantIdentifierResolver<UUID>, HibernatePropertiesCustomizer {

    static final UUID NO_BUSINESS = new UUID(0, 0);

    @Override
    public UUID resolveCurrentTenantIdentifier() {
        return TenantScope.current().orElse(NO_BUSINESS);
    }

    /** Sessions live for one transaction, and TenantScope refuses to switch inside one. */
    @Override
    public boolean validateExistingCurrentSessions() {
        return false;
    }

    @Override
    public void customize(Map<String, Object> hibernateProperties) {
        hibernateProperties.put(AvailableSettings.MULTI_TENANT_IDENTIFIER_RESOLVER, this);
    }
}
