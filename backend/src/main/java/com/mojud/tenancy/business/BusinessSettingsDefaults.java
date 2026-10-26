package com.mojud.tenancy.business;

import com.mojud.shared.error.ApiException;
import com.mojud.shared.tenant.TenantScope;
import com.mojud.tenancy.BusinessDefaults;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
class BusinessSettingsDefaults implements BusinessDefaults {

    private final BusinessSettingsRepository settings;

    BusinessSettingsDefaults(BusinessSettingsRepository settings) {
        this.settings = settings;
    }

    @Override
    @Transactional(readOnly = true)
    public int lowStockDefault() {
        return settings.findById(TenantScope.current().orElseThrow())
                .map(BusinessSettings::getLowStockDefault)
                .orElseThrow(ApiException::notFound);
    }
}
