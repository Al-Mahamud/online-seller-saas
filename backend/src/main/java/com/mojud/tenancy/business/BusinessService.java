package com.mojud.tenancy.business;

import com.mojud.shared.audit.AuditTrail;
import com.mojud.shared.audit.Changes;
import com.mojud.shared.db.AdvisoryLocks;
import com.mojud.shared.db.Constraints;
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
import java.util.Map;
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
    private final AdvisoryLocks locks;
    private final AuditTrail audit;
    private final Clock clock;
    private final int maxOwnedBusinesses;

    BusinessService(BusinessRepository businesses, BusinessSettingsRepository settings,
            MembershipRepository memberships, MembershipDirectory directory, TransactionTemplate transactions,
            AdvisoryLocks locks, AuditTrail audit, Clock clock,
            @Value("${mojud.tenancy.max-owned-businesses}") int maxOwnedBusinesses) {
        this.businesses = businesses;
        this.settings = settings;
        this.memberships = memberships;
        this.directory = directory;
        this.transactions = transactions;
        this.locks = locks;
        this.audit = audit;
        this.clock = clock;
        this.maxOwnedBusinesses = maxOwnedBusinesses;
    }

    /**
     * Creates a business with default settings and the caller as its owner. One user's creations
     * take turns (a transaction-scoped advisory lock), so the owned-business count is always read
     * after the previous creation committed and parallel requests cannot pass the limit together.
     */
    BusinessResponse create(UUID userId, String name, String phone, String address) {
        PhoneNumber businessPhone = PhoneNumber.fromOptionalInput(phone, "phone");
        UUID businessId = Ids.newId();
        return TenantScope.call(businessId, () -> transactions.execute(status -> {
            locks.lockForTransaction(OwnedBusinesses.lockKey(userId));
            if (directory.ownedBusinessCount(userId) >= maxOwnedBusinesses) {
                throw new ApiException(ErrorCode.BUSINESS_LIMIT_REACHED, maxOwnedBusinesses);
            }
            Instant now = clock.instant();
            Business business = new Business(businessId, name, now);
            business.changeContact(businessPhone, address, now);
            businesses.saveAndFlush(business);
            settings.save(BusinessSettings.defaults(businessId, now));
            memberships.save(new Membership(userId, BusinessRole.OWNER, now));
            audit.record(businessId, userId, "business.created", "business", businessId, "name", business.getName());
            return BusinessResponse.of(business, BusinessRole.OWNER);
        }));
    }

    /**
     * Checks the caller is an active member of an active business and remembers the switch, so
     * this business is selected again after the next sign-in.
     *
     * @throws ApiException {@code resource.not_found} for a business the caller does not belong to,
     *         or one that is suspended
     */
    BusinessResponse switchTo(UUID userId, UUID businessId) {
        return TenantScope.call(businessId, () -> transactions.execute(status -> {
            Membership membership = memberships.findActive(userId).orElseThrow(ApiException::notFound);
            Business business = businesses.findById(businessId).orElseThrow(ApiException::notFound);
            memberships.markUsed(membership.getId(), clock.instant());
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
        BusinessResponse before = BusinessResponse.of(business, current.role());
        Instant now = clock.instant();
        if (name != null) {
            business.rename(name, now);
        }
        if (phone != null || address != null) {
            PhoneNumber newPhone = phone == null ? business.getPhone().orElse(null)
                    : PhoneNumber.fromOptionalInput(phone, "phone");
            business.changeContact(newPhone, address == null ? business.getAddress() : address, now);
        }
        BusinessResponse after = BusinessResponse.of(businesses.saveAndFlush(business), current.role());
        Changes changes = new Changes()
                .field("name", before.name(), after.name())
                .field("phone", before.phone(), after.phone())
                .field("address", before.address(), after.address());
        if (!changes.isEmpty()) {
            audit.record(current.businessId(), current.userId(), "business.updated", "business", current.businessId(),
                    "changes", changes.asMap());
        }
        return after;
    }

    /**
     * Shows an uploaded file ({@code POST /uploads}) as the business's logo, or none when
     * {@code fileId} is null. Another business's file looks like one that does not exist.
     */
    BusinessResponse changeLogo(UUID fileId) {
        CurrentBusiness current = CurrentBusiness.require();
        return Constraints.translating(transactions, Map.of("fk_businesses_logo", ErrorCode.NOT_FOUND), () -> {
            Business business = load(current.businessId());
            UUID before = business.getLogoFileId();
            business.changeLogo(fileId, clock.instant());
            businesses.saveAndFlush(business);
            if (!java.util.Objects.equals(before, fileId)) {
                audit.record(current.businessId(), current.userId(), "business.updated", "business",
                        current.businessId(), "changes", new Changes().field("logo", before, fileId).asMap());
            }
            return BusinessResponse.of(business, current.role());
        });
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
        SettingsResponse before = SettingsResponse.of(current);
        current.change(commitPoint, lowStockDefault, prefix, footer, clock.instant());
        SettingsResponse after = SettingsResponse.of(settings.saveAndFlush(current));
        Changes changes = new Changes()
                .field("stockCommitPoint", before.stockCommitPoint(), after.stockCommitPoint())
                .field("lowStockDefault", before.lowStockDefault(), after.lowStockDefault())
                .field("orderNumberPrefix", before.orderNumberPrefix(), after.orderNumberPrefix())
                .field("invoiceFooter", before.invoiceFooter(), after.invoiceFooter());
        if (!changes.isEmpty()) {
            CurrentBusiness business = CurrentBusiness.require();
            audit.record(business.businessId(), business.userId(), "business.settings_changed", "business",
                    business.businessId(), "changes", changes.asMap());
        }
        return after;
    }

    private Business load(UUID businessId) {
        return businesses.findById(businessId).orElseThrow(ApiException::notFound);
    }

    private BusinessSettings loadSettings() {
        return settings.findById(CurrentBusiness.require().businessId()).orElseThrow(ApiException::notFound);
    }
}
