package com.mojud.tenancy.business;

import com.mojud.shared.error.ApiException;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** A business's name and status for other parts of the tenancy module, inside its TenantScope. */
@Component
public class BusinessNames {

    private final BusinessRepository businesses;

    BusinessNames(BusinessRepository businesses) {
        this.businesses = businesses;
    }

    /** @throws ApiException {@code resource.not_found} outside the business's TenantScope */
    @Transactional(readOnly = true)
    public String nameOf(UUID businessId) {
        return businesses.findById(businessId).map(Business::getName).orElseThrow(ApiException::notFound);
    }

    @Transactional(readOnly = true)
    public boolean isActive(UUID businessId) {
        return businesses.findById(businessId).map(Business::isActive).orElse(false);
    }
}
