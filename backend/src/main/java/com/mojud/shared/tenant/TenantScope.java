package com.mojud.shared.tenant;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The business the current thread works for. Every database connection the application takes
 * carries it to PostgreSQL, where row-level security filters on it (backend-plan.md §4).
 *
 * <p>The tenancy module opens a scope per request from the signed-in session (Phase 2a); jobs open
 * one per business they process. Code outside a scope sees no tenant rows at all.
 */
public final class TenantScope {

    private static final ThreadLocal<UUID> CURRENT = new ThreadLocal<>();

    private TenantScope() {
    }

    public static Optional<UUID> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    public static void run(UUID businessId, Runnable work) {
        call(businessId, () -> {
            work.run();
            return null;
        });
    }

    /**
     * Runs {@code work} for {@code businessId} and restores the previous scope afterwards.
     *
     * @throws IllegalStateException when called inside a transaction for a different business: the
     *         transaction's connection already carries the old business, so the switch would not
     *         reach the database.
     */
    public static <T> T call(UUID businessId, Supplier<T> work) {
        try (Opened ignored = open(businessId)) {
            return work.get();
        }
    }

    /**
     * Opens a scope for {@code businessId} until {@link Opened#close()}, for code where a lambda is
     * awkward, such as a servlet filter whose chain throws checked exceptions:
     * <pre>{@code try (TenantScope.Opened ignored = TenantScope.open(businessId)) { chain.doFilter(...); }}</pre>
     *
     * @throws IllegalStateException as for {@link #call}
     */
    public static Opened open(UUID businessId) {
        Objects.requireNonNull(businessId, "businessId");
        UUID previous = CURRENT.get();
        if (!businessId.equals(previous) && TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Cannot switch business inside a transaction");
        }
        CURRENT.set(businessId);
        return new Opened(previous);
    }

    /** An open scope; closing it restores the scope that was current before. */
    public static final class Opened implements AutoCloseable {

        private final UUID previous;

        private Opened(UUID previous) {
            this.previous = previous;
        }

        @Override
        public void close() {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }
}
