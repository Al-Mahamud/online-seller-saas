package com.mojud.shared.db;

import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Makes work on one thing take turns across requests, when there is no single row to lock: for
 * example "creations by this user" or "invites to this address". The lock is PostgreSQL's
 * transaction-scoped advisory lock on a hash of the key, held until the transaction ends.
 */
@Component
public class AdvisoryLocks {

    private final EntityManager entityManager;

    AdvisoryLocks(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    /**
     * Waits until no other transaction holds {@code key}, then holds it until this one ends.
     *
     * @throws IllegalStateException outside a transaction, where the lock would end at once
     */
    public void lockForTransaction(String key) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Advisory locks need a transaction");
        }
        entityManager.createNativeQuery("select count(*) from (select pg_advisory_xact_lock(hashtextextended(?1, 0))) l")
                .setParameter(1, key)
                .getSingleResult();
    }
}
