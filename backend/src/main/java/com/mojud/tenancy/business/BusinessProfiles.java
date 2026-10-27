package com.mojud.tenancy.business;

import com.mojud.shared.error.ApiException;
import com.mojud.shared.phone.PhoneNumber;
import com.mojud.shared.tenant.TenantScope;
import com.mojud.tenancy.BusinessProfile;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
class BusinessProfiles implements BusinessProfile {

    private final BusinessRepository businesses;
    private final BusinessSettingsRepository settings;

    BusinessProfiles(BusinessRepository businesses, BusinessSettingsRepository settings) {
        this.businesses = businesses;
        this.settings = settings;
    }

    @Override
    @Transactional(readOnly = true)
    public Profile current() {
        UUID id = TenantScope.current().orElseThrow();
        Business business = businesses.findById(id).orElseThrow(ApiException::notFound);
        String footer = settings.findById(id).map(BusinessSettings::getInvoiceFooter).orElse("");
        return new Profile(business.getName(), business.getPhone().map(PhoneNumber::e164).orElse(null),
                business.getAddress(), business.getLogoFileId(), footer);
    }
}
