package com.mojud.tenancy;

import static org.assertj.core.api.Assertions.assertThat;

import com.mojud.shared.tenant.TenantScope;
import com.mojud.support.ApiClient;
import com.mojud.support.CapturedMessages;
import com.mojud.support.IntegrationTest;
import com.mojud.support.TestAccounts;
import com.mojud.support.TestBusinesses;
import com.mojud.tenancy.membership.Membership;
import com.mojud.tenancy.membership.MembershipRepository;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Layer 1 of tenant isolation on its own: Hibernate's {@code @TenantId} filter must keep
 * businesses apart even when the database's row-level security points somewhere else.
 */
@IntegrationTest
class TenantIdTests {

    @LocalServerPort
    int port;

    @Autowired
    CapturedMessages messages;

    @Autowired
    MembershipRepository memberships;

    @Autowired
    TransactionTemplate transactions;

    @Autowired
    EntityManager entityManager;

    @Test
    void hibernateFiltersByTheCurrentBusinessEvenIfTheDatabaseSettingDisagrees() {
        UUID businessA = business("A");
        UUID businessB = business("B");

        List<Object> seenWithoutHibernate = new java.util.ArrayList<>();
        List<Membership> seen = TenantScope.call(businessA, () -> transactions.execute(status -> {
            // Point row-level security at B: only Hibernate's filter still says A.
            entityManager.createNativeQuery("select set_config('app.business_id', ?1, false)")
                    .setParameter(1, businessB.toString()).getSingleResult();
            // Control: plain SQL, which Hibernate does not filter, now sees B's owner.
            seenWithoutHibernate.addAll(entityManager.createNativeQuery("select id from memberships").getResultList());
            return memberships.findAll();
        }));

        assertThat(seenWithoutHibernate).hasSize(1);
        assertThat(seen).isEmpty();
    }

    @Test
    void insertsTakeTheCurrentBusiness() {
        UUID business = business("Inserting");
        ApiClient newcomer = new ApiClient(port);
        TestAccounts.signUp(newcomer, messages);
        UUID userId = TestBusinesses.userId(newcomer);

        Membership saved = TenantScope.call(business, () -> transactions.execute(status ->
                memberships.saveAndFlush(new Membership(userId, BusinessRole.STAFF, Instant.now()))));

        assertThat(saved.getBusinessId()).isEqualTo(business);
    }

    @Test
    void outsideAnyBusinessNothingIsFound() {
        business("Invisible");

        List<Membership> found = transactions.execute(status -> memberships.findAll());

        assertThat(found).isEmpty();
    }

    @Test
    void anotherBusinessesRowIsNotFoundById() {
        UUID businessA = business("Owner A");
        UUID businessB = business("Owner B");
        UUID membershipInB = TenantScope.call(businessB, () -> transactions.execute(status ->
                memberships.findAll().get(0).getId()));

        Optional<Membership> found = TenantScope.call(businessA, () -> transactions.execute(status ->
                memberships.findById(membershipInB)));

        assertThat(found).isEmpty();
    }

    private ApiClient owner;

    private UUID business(String name) {
        owner = new ApiClient(port);
        TestAccounts.signUp(owner, messages);
        return TestBusinesses.create(owner, name);
    }
}
