package com.mojud.tenancy.business;

import com.mojud.shared.error.ApiException;
import com.mojud.shared.error.ErrorCode;
import com.mojud.shared.id.Ids;
import com.mojud.shared.phone.PhoneNumber;
import com.mojud.shared.tenant.TenantScope;
import com.mojud.tenancy.BusinessRole;
import com.mojud.tenancy.CurrentBusiness;
import com.mojud.tenancy.membership.Membership;
import com.mojud.tenancy.membership.MembershipRepository;
import com.mojud.tenancy.system.MembershipDirectory;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Creating, selecting and editing businesses. Methods that work in a business other than the
 * request's (creating one, switching) open that business's TenantScope themselves and so are not
 * transactional: the scope cannot change inside a transaction.
 */
@Service
class BusinessService {

    private final BusinessRepository businesses;
    private final BusinessSettingsRepository settings;
    private final MembershipRepository memberships;
    private final MembershipDirectory directory;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final int maxOwnedBusinesses;

    BusinessService(BusinessRepository businesses, BusinessSettingsRepository settings,
            MembershipRepository memberships, MembershipDirectory directory, TransactionTemplate transactions,
            Clock clock, @Value("${mojud.tenancy.max-owned-businesses}") int maxOwnedBusinesses) {
        this.businesses = businesses;
        this.settings = settings;
        this.memberships = memberships;
        this.directory = directory;
        this.transactions = transactions;
        this.clock = clock;
        this.maxOwnedBusinesses = maxOwnedBusinesses;
    }

    /** Creates a business with default settings and the caller as its owner. */
    BusinessResponse create(UUID userId, String name, PhoneNumber phone, String address) {
        if (directory.ownedBusinessCount(userId) >= maxOwnedBusinesses) {
            throw new ApiException(ErrorCode.BUSINESS_LIMIT_REACHED, maxOwnedBusinesses);
        }
        UUID businessId = Ids.newId();
        return TenantScope.call(businessId, () -> transactions.execute(status -> {
            Instant now = clock.instant();
            Business business = new Business(businessId, name, now);
            business.changeContact(phone, address, now);
            businesses.saveAndFlush(business);
            settings.save(BusinessSettings.defaults(businessId, now));
            memberships.save(new Membership(userId, BusinessRole.OWNER, now));
            return BusinessResponse.of(business, BusinessRole.OWNER);
        }));
    }

    /**
     * Checks the caller is an active member and remembers the switch, so this business is
     * selected again after the next sign-in.
     *
     * @throws ApiException {@code resource.not_found} for a business the caller does not belong to
     */
    BusinessResponse switchTo(UUID userId, UUID businessId) {
        return TenantScope.call(businessId, () -> transactions.execute(status -> {
            Membership membership = memberships.findByUserId(userId).filter(Membership::isActive)
                    .orElseThrow(ApiException::notFound);
            Business business = businesses.findById(businessId).orElseThrow(ApiException::notFound);
            membership.markUsed(clock.instant());
            return BusinessResponse.of(business, membership.getRole());
        }));
    }

    List<MembershipDirectory.UserBusiness> businessesOf(UUID userId) {
        return directory.businessesOf(userId);
    }

    @Transactional(readOnly = true)
    BusinessResponse current() {
        CurrentBusiness current = CurrentBusiness.require();
        return BusinessResponse.of(load(current.businessId()), current.role());
    }

    /** Null arguments keep the current value; an empty phone or address clears it. */
    @Transactional
    BusinessResponse update(String name, String phone, String address) {
        CurrentBusiness current = CurrentBusiness.require();
        Business business = load(current.businessId());
        Instant now = clock.instant();
        if (name != null) {
            business.rename(name, now);
        }
        if (phone != null || address != null) {
            PhoneNumber newPhone = phone == null ? business.getPhone().orElse(null)
                    : phone.isBlank() ? null
                    : PhoneNumber.tryParse(phone).orElseThrow(() -> ApiException.invalidField("phone", "field.phone_invalid"));
            business.changeContact(newPhone, address == null ? business.getAddress() : address, now);
        }
        return BusinessResponse.of(businesses.saveAndFlush(business), current.role());
    }

    @Transactional(readOnly = true)
    SettingsResponse settings() {
        return SettingsResponse.of(loadSettings());
    }

    /** Null arguments keep the current value. */
    @Transactional
    SettingsResponse updateSettings(StockCommitPoint commitPoint, Integer lowStockDefault, String prefix,
            String footer) {
        BusinessSettings current = loadSettings();
        current.change(commitPoint, lowStockDefault, prefix, footer, clock.instant());
        return SettingsResponse.of(settings.saveAndFlush(current));
    }

    private Business load(UUID businessId) {
        return businesses.findById(businessId).orElseThrow(ApiException::notFound);
    }

    private BusinessSettings loadSettings() {
        return settings.findById(CurrentBusiness.require().businessId()).orElseThrow(ApiException::notFound);
    }
}
