package com.mojud.inventory;

import com.mojud.shared.error.ApiException;
import com.mojud.shared.error.ErrorCode;
import com.mojud.shared.id.Ids;
import com.mojud.shared.tenant.TenantScope;
import com.mojud.tenancy.CurrentBusiness;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The only way stock changes (backend-plan.md §6). Every method runs in the caller's transaction
 * (an adjustment, an order change), so the stock and what caused it commit together or not at
 * all, and each:
 * <ol>
 * <li>locks the variants' stock rows, always in variant-id order, so two requests that touch the
 * same variants take turns and never deadlock;</li>
 * <li>checks the change is possible (no negative stock, no reserving or damaging more than is
 * available, no releasing more than is reserved);</li>
 * <li>updates the cached level and appends one ledger entry per variant with the level after it;</li>
 * <li>publishes {@link StockChanged}, handled in the same transaction (low-stock alerts).</li>
 * </ol>
 * A variant of another business (or none) is "not found": the foreign keys refuse it.
 */
@Service
public class StockService {

    /** A quantity of one variant; quantities are always positive. */
    public record Line(UUID variantId, int quantity) {
    }

    /** What caused a change, for reading the history later. */
    /**
     * What caused a change, and who: {@code actorId} names the user when no member is signed in
     * (an import running in the background); null means the signed-in member, if any.
     */
    public record Reference(String type, UUID id, String note, UUID actorId) {

        public Reference(String type, UUID id, String note) {
            this(type, id, note, null);
        }

        /** No cause to point at, such as opening stock entered with a new product. */
        public static Reference none() {
            return new Reference(null, null, null);
        }

        public static Reference adjustment(UUID batchId, String note) {
            return new Reference("ADJUSTMENT", batchId, note);
        }

        public static Reference order(UUID orderId) {
            return new Reference("ORDER", orderId, null);
        }

        public static Reference returned(UUID returnId) {
            return new Reference("RETURN", returnId, null);
        }

        public static Reference imported(UUID importJobId, UUID committedBy) {
            return new Reference("IMPORT", importJobId, null, committedBy);
        }
    }

    private record Delta(int onHand, int reserved) {
    }

    private final JdbcClient jdbc;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    StockService(DataSource dataSource, ApplicationEventPublisher events, Clock clock) {
        this.jdbc = JdbcClient.create(dataSource);
        this.events = events;
        this.clock = clock;
    }

    // Shelf stock.

    @Transactional(propagation = Propagation.MANDATORY)
    public List<StockLevel> opening(List<Line> lines, Reference reference) {
        return apply(MovementType.OPENING, lines, reference, (level, q) -> new Delta(q, 0));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public List<StockLevel> receive(List<Line> lines, Reference reference) {
        return apply(MovementType.RECEIVE, lines, reference, (level, q) -> new Delta(q, 0));
    }

    /** Only available stock can be damaged: what is reserved belongs to orders. */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<StockLevel> damage(List<Line> lines, Reference reference) {
        return apply(MovementType.DAMAGE, lines, reference, (level, q) -> {
            requireAvailable(level, q);
            return new Delta(-q, 0);
        });
    }

    /**
     * Sets on hand to what was counted. A count below what is reserved is refused: confirmed orders
     * hold that stock, so cancel or ship them first.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<StockLevel> count(UUID variantId, int counted, Reference reference) {
        if (counted < 0) {
            throw new ApiException(ErrorCode.STOCK_INVALID_CHANGE);
        }
        return apply(MovementType.ADJUST, List.of(new Line(variantId, counted)), reference, (level, q) -> {
            if (q < level.reserved()) {
                throw new ApiException(ErrorCode.STOCK_INSUFFICIENT);
            }
            return new Delta(q - level.onHand(), 0);
        }, true);
    }

    /**
     * Locks these variants' stock rows now, in variant-id order. For a caller about to make several
     * changes in one transaction (an order edit that reserves some variants and releases others):
     * taking every lock first, in one order, means two such callers cannot deadlock.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockAll(Collection<UUID> variantIds) {
        if (!variantIds.isEmpty()) {
            TreeMap<UUID, Integer> ids = new TreeMap<>();
            variantIds.forEach(id -> ids.put(id, 0));
            lock(TenantScope.current().orElseThrow(), ids);
        }
    }

    // Orders (Phase 7).

    @Transactional(propagation = Propagation.MANDATORY)
    public List<StockLevel> reserve(List<Line> lines, Reference reference) {
        return apply(MovementType.RESERVE, lines, reference, (level, q) -> {
            requireAvailable(level, q);
            return new Delta(0, q);
        });
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public List<StockLevel> release(List<Line> lines, Reference reference) {
        return apply(MovementType.RELEASE, lines, reference, (level, q) -> {
            requireReserved(level, q);
            return new Delta(0, -q);
        });
    }

    /** Reserved goods leave with the courier. */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<StockLevel> ship(List<Line> lines, Reference reference) {
        return apply(MovementType.SHIP, lines, reference, (level, q) -> {
            requireReserved(level, q);
            return new Delta(-q, -q);
        });
    }

    /** Deduct at confirm (stock_commit_point CONFIRM): no reservation step. */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<StockLevel> sell(List<Line> lines, Reference reference) {
        return apply(MovementType.SALE, lines, reference, (level, q) -> {
            requireAvailable(level, q);
            return new Delta(-q, 0);
        });
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public List<StockLevel> unsell(List<Line> lines, Reference reference) {
        return apply(MovementType.UNSALE, lines, reference, (level, q) -> new Delta(q, 0));
    }

    // Returns (Phase 8).

    @Transactional(propagation = Propagation.MANDATORY)
    public List<StockLevel> restock(List<Line> lines, Reference reference) {
        return apply(MovementType.RETURN, lines, reference, (level, q) -> new Delta(q, 0));
    }

    /** Recorded for reports; the goods never come back on the shelf. */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<StockLevel> writeOffReturn(List<Line> lines, Reference reference) {
        return apply(MovementType.RETURN_DAMAGED, lines, reference, (level, q) -> new Delta(0, 0));
    }

    // The mechanics.

    @FunctionalInterface
    private interface Rule {

        Delta change(StockLevel level, int quantity);
    }

    private List<StockLevel> apply(MovementType type, List<Line> lines, Reference reference, Rule rule) {
        return apply(type, lines, reference, rule, false);
    }

    /**
     * @param zeroAllowed a count may be 0 (and may change nothing); other quantities are positive
     */
    private List<StockLevel> apply(MovementType type, List<Line> lines, Reference reference, Rule rule,
            boolean zeroAllowed) {
        UUID business = TenantScope.current().orElseThrow(() -> new IllegalStateException("No business"));
        // One quantity per variant, in variant-id order: the lock order every request shares.
        TreeMap<UUID, Integer> quantities = new TreeMap<>();
        for (Line line : lines) {
            if (line.quantity() < 0 || (!zeroAllowed && line.quantity() == 0)) {
                throw new ApiException(ErrorCode.STOCK_INVALID_CHANGE);
            }
            quantities.merge(line.variantId(), line.quantity(), Integer::sum);
        }
        if (quantities.isEmpty()) {
            return List.of();
        }
        Map<UUID, StockLevel> levels = lock(business, quantities);

        List<Map<String, Object>> shortages = new ArrayList<>();
        Map<UUID, Delta> deltas = new LinkedHashMap<>();
        quantities.forEach((variantId, quantity) -> {
            StockLevel level = levels.get(variantId);
            try {
                deltas.put(variantId, rule.change(level, quantity));
            } catch (ApiException e) {
                if (e.code() != ErrorCode.STOCK_INSUFFICIENT) {
                    throw e;
                }
                shortages.add(Map.of("variantId", variantId, "requested", quantity, "available", level.available()));
            }
        });
        if (!shortages.isEmpty()) {
            throw new ApiException(ErrorCode.STOCK_INSUFFICIENT).withDetail("shortages", shortages);
        }

        Instant now = clock.instant();
        UUID user = reference.actorId() != null ? reference.actorId()
                : CurrentBusiness.get().map(CurrentBusiness::userId).orElse(null);
        List<StockLevel> after = new ArrayList<>();
        List<StockChanged.Change> changes = new ArrayList<>();
        deltas.forEach((variantId, delta) -> {
            StockLevel level = levels.get(variantId);
            StockLevel next = new StockLevel(variantId, level.onHand() + delta.onHand(), level.reserved() + delta.reserved());
            jdbc.sql("update stock_levels set on_hand = ?, reserved = ?, updated_at = ? where business_id = ? and variant_id = ?")
                    .params(next.onHand(), next.reserved(), Timestamp.from(now), business, variantId)
                    .update();
            jdbc.sql("""
                            insert into stock_movements (id, business_id, variant_id, type, on_hand_delta, reserved_delta,
                                on_hand_after, reserved_after, reference_type, reference_id, note, user_id, created_at)
                            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                            """)
                    .params(Ids.newId(), business, variantId, type.name(), delta.onHand(), delta.reserved(),
                            next.onHand(), next.reserved(), reference.type(), reference.id(), reference.note(), user,
                            Timestamp.from(now))
                    .update();
            after.add(next);
            changes.add(new StockChanged.Change(variantId, level.available(), next.available()));
        });
        events.publishEvent(new StockChanged(type, changes));
        return after;
    }

    /** Creates missing rows (0 / 0), then locks them all in variant-id order. */
    private Map<UUID, StockLevel> lock(UUID business, TreeMap<UUID, Integer> quantities) {
        Timestamp now = Timestamp.from(clock.instant());
        try {
            for (UUID variantId : quantities.keySet()) {
                jdbc.sql("""
                                insert into stock_levels (business_id, variant_id, updated_at) values (?, ?, ?)
                                on conflict (variant_id) do nothing
                                """)
                        .params(business, variantId, now)
                        .update();
            }
        } catch (DataIntegrityViolationException e) {
            // fk_stock_levels_variant: no such variant in this business.
            throw ApiException.notFound();
        }
        Map<UUID, StockLevel> levels = new LinkedHashMap<>();
        jdbc.sql("""
                        select variant_id, on_hand, reserved from stock_levels
                        where business_id = :business and variant_id in (:ids)
                        order by variant_id for update
                        """)
                .param("business", business)
                .param("ids", quantities.keySet())
                .query((row, n) -> new StockLevel(row.getObject(1, UUID.class), row.getInt(2), row.getInt(3)))
                .list()
                .forEach(level -> levels.put(level.variantId(), level));
        if (levels.size() != quantities.size()) {
            // A row of another business: invisible here, so treated as missing.
            throw ApiException.notFound();
        }
        return levels;
    }

    private static void requireAvailable(StockLevel level, int quantity) {
        if (level.available() < quantity) {
            throw new ApiException(ErrorCode.STOCK_INSUFFICIENT);
        }
    }

    private static void requireReserved(StockLevel level, int quantity) {
        if (level.reserved() < quantity) {
            throw new ApiException(ErrorCode.STOCK_INVALID_CHANGE);
        }
    }
}
