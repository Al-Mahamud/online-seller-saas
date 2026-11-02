package com.mojud.orders;

import com.mojud.shared.text.Text;
import com.mojud.shared.audit.AuditTrail;
import com.mojud.shared.time.DhakaTime;
import com.mojud.shared.error.ApiException;
import com.mojud.shared.error.ErrorCode;
import com.mojud.shared.paging.PageQuery;
import com.mojud.shared.paging.PagedResult;
import com.mojud.tenancy.CurrentBusiness;
import java.time.Clock;
import java.text.Normalizer;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * COD money with couriers (implementation-plan.md 8b): what each shipment was to collect, what it
 * collected, and settling a courier's payout, which covers many shipments at once.
 */
@Service
class CodSettlements {

    static final int MAX_LIST = 500;
    static final int MAX_SETTLE = 500;
    /** The most one search can settle at once; a query can't take many more ids. Dates narrow a larger one. */
    static final int MAX_SETTLE_MATCHING = 10_000;
    private static final Instant START_OF_TIME = Instant.parse("2000-01-01T00:00:00Z");
    private static final Instant END_OF_TIME = Instant.parse("2200-01-01T00:00:00Z");
    private static final int MAX_SEARCH = 100;

    /** Money in poisha. */
    record Row(UUID id, UUID orderId, String orderNumberLabel, String courier, String trackingNo, String status,
            long codExpected, @Nullable Long codCollected, String codStatus, Instant shippedAt, @Nullable Instant deliveredAt,
            @Nullable Instant settledAt) {
    }

    /** One parcel a courier owes for. Money in poisha. */
    record Parcel(UUID id, UUID orderId, String orderNumberLabel, String customerName, @Nullable String trackingNo,
            long codExpected, @Nullable Long codCollected, @Nullable Instant deliveredAt) {
    }

    /**
     * One courier's COD money. {@code owedAmount} and {@code inTransitAmount} are all of it;
     * {@code matchedAmount} and {@code matchedParcels} are what's owed under the search.
     */
    record CourierCod(String name, long owedAmount, long owedParcels, long inTransitAmount, long matchedAmount,
            long matchedParcels) {
    }

    /** Everything couriers hold, whatever the search, and each courier, the one owed the most first. */
    record Summary(long owedAmount, long owedParcels, long inTransitAmount, List<CourierCod> couriers) {
    }

    /** A search over what's owed: dates of delivery, and text. */
    record Search(@Nullable LocalDate from, @Nullable LocalDate to, @Nullable String q) {

        Instant start() {
            return from == null ? START_OF_TIME : DhakaTime.startOf(from);
        }

        Instant end() {
            return to == null ? END_OF_TIME : DhakaTime.startOf(to.plusDays(1));
        }

        /** As it's compared: lower case, one form of Bangla, and Latin digits, as order numbers and phones are kept. */
        String text() {
            if (q == null) {
                return "";
            }
            String cleaned = Normalizer.normalize(q, Normalizer.Form.NFC).strip().toLowerCase(Locale.ROOT);
            StringBuilder latin = new StringBuilder();
            cleaned.codePoints().limit(MAX_SEARCH).forEach(point -> latin.appendCodePoint(
                    Character.isDigit(point) ? '0' + Character.digit(point, 10) : point));
            return latin.toString();
        }

        String pattern() {
            return Text.containsPattern(text());
        }
    }

    /** A payout: every parcel of a courier under a search, less the ones left out, as the person saw it. */
    record SettleMatching(String courier, Search search, Set<UUID> exceptIds, long expectedParcels, long expectedAmount) {
    }

    /** {@code amount}: the COD collected on the shipments settled, in poisha. */
    record Settlement(String courier, int shipments, long amount, Instant settledAt) {
    }

    private final ShipmentRepository shipments;
    private final OrderRepository orders;
    private final AuditTrail audit;
    private final Clock clock;

    CodSettlements(ShipmentRepository shipments, OrderRepository orders, AuditTrail audit, Clock clock) {
        this.shipments = shipments;
        this.orders = orders;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * Newest first, up to 500. {@code codStatus} WITH_COURIER and {@code status} DELIVERED is what
     * a courier owes; WITH_COURIER and IN_TRANSIT is still to be collected.
     */
    @Transactional(readOnly = true)
    List<Row> list(String status, String codStatus, String courier) {
        List<Shipment> found = shipments.findFiltered(status == null ? "" : status, codStatus == null ? "" : codStatus,
                courier == null ? "" : courier.strip().toLowerCase(Locale.ROOT), Limit.of(MAX_LIST));
        Map<UUID, Order> byId = orders.findAllById(found.stream().map(Shipment::getOrderId).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(Order::getId, Function.identity()));
        return found.stream().map(s -> new Row(s.getId(), s.getOrderId(), byId.get(s.getOrderId()).getNumberLabel(),
                s.getCourier(), s.getTrackingNo(), s.getStatus(), s.getCodExpected(), s.getCodCollected(), s.getCodStatus(),
                s.getShippedAt(), s.getDeliveredAt(), s.getSettledAt())).toList();
    }

    /** What's owed and on the way, and what of it a search finds, per courier. */
    @Transactional(readOnly = true)
    Summary summary(Search search) {
        Map<String, long[]> matched = new HashMap<>();
        shipments.sumOwed(search.start(), search.end(), search.text(), search.pattern())
                .forEach(row -> matched.put((String) row[0], new long[] {((Number) row[1]).longValue(), ((Number) row[2]).longValue()}));
        Map<String, String> names = new HashMap<>();
        Map<String, long[]> owed = new HashMap<>();
        Map<String, Long> inTransit = new HashMap<>();
        for (Object[] row : shipments.sumWithCouriers()) {
            String key = (String) row[0];
            names.merge(key, (String) row[1], (one, other) -> one.compareTo(other) <= 0 ? one : other);
            long parcels = ((Number) row[3]).longValue();
            long amount = ((Number) row[4]).longValue();
            if ("DELIVERED".equals(row[2])) {
                owed.put(key, new long[] {parcels, amount});
            } else {
                inTransit.put(key, amount);
            }
        }
        long[] none = {0, 0};
        List<CourierCod> couriers = names.entrySet().stream().map(entry -> {
            String key = entry.getKey();
            long[] owes = owed.getOrDefault(key, none);
            long[] found = matched.getOrDefault(key, none);
            return new CourierCod(entry.getValue(), owes[1], owes[0], inTransit.getOrDefault(key, 0L), found[1], found[0]);
        }).sorted(Comparator.comparingLong(CourierCod::owedAmount).reversed().thenComparing(CourierCod::name)).toList();
        return new Summary(couriers.stream().mapToLong(CourierCod::owedAmount).sum(),
                couriers.stream().mapToLong(CourierCod::owedParcels).sum(),
                couriers.stream().mapToLong(CourierCod::inTransitAmount).sum(), couriers);
    }

    /** One page of a courier's parcels under a search, last delivered first. */
    @Transactional(readOnly = true)
    PagedResult<Parcel> parcels(String courier, Search search, PageQuery paging) {
        return PagedResult.of(shipments.findOwedPage(courierKey(courier), search.start(), search.end(), search.text(),
                search.pattern(), paging.pageable()), paging).mapItems(this::parcelsOf);
    }

    private List<Parcel> parcelsOf(List<Shipment> page) {
        Map<UUID, Order> byId = orders.findAllById(page.stream().map(Shipment::getOrderId).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(Order::getId, Function.identity()));
        return page.stream().map(s -> {
            Order order = byId.get(s.getOrderId());
            return new Parcel(s.getId(), s.getOrderId(), order.getNumberLabel(), order.parties().customerName(), s.getTrackingNo(),
                    s.getCodExpected(), s.getCodCollected(), s.getDeliveredAt());
        }).toList();
    }

    private static String courierKey(String courier) {
        return courier.strip().toLowerCase(Locale.ROOT);
    }

    /**
     * Settles a courier's parcels under a search, across all its pages, less what was left out. It goes
     * through only if it's what the person was shown: parcels can be delivered or settled meanwhile, and
     * nobody should mark money received that they didn't see. A refusal says what's true now.
     */
    @Transactional
    Settlement settleMatching(SettleMatching payout) {
        Search search = payout.search();
        Set<UUID> ids = new HashSet<>(shipments.findOwedIds(courierKey(payout.courier()), search.start(), search.end(),
                search.text(), search.pattern()));
        Set<UUID> stillLeftOut = payout.exceptIds().stream().filter(ids::contains).collect(Collectors.toSet());
        ids.removeAll(stillLeftOut);
        if (ids.size() > MAX_SETTLE_MATCHING) {
            throw new ApiException(ErrorCode.COD_TOO_MANY);
        }
        List<Shipment> locked = ids.isEmpty() ? List.of() : shipments.findLocked(ids);
        // Someone may have settled some while this waited for the locks.
        List<Shipment> settleable = locked.stream().filter(CodSettlements::settleable).toList();
        long amount = settleable.stream().mapToLong(Shipment::getCodCollected).sum();
        if (settleable.isEmpty() || settleable.size() != payout.expectedParcels() || amount != payout.expectedAmount()) {
            throw new ApiException(ErrorCode.COD_LIST_CHANGED).withDetail("exceptIds", stillLeftOut.stream().sorted().toList());
        }
        return markSettled(settleable);
    }

    private static boolean settleable(Shipment shipment) {
        return "DELIVERED".equals(shipment.getStatus()) && "WITH_COURIER".equals(shipment.getCodStatus());
    }

    /**
     * Marks these shipments' COD as paid out to the business: all or none, all of one courier, each
     * delivered with its collected cash still with the courier. Rows are locked, so a shipment is
     * settled once even when two people settle the same payout.
     */
    @Transactional
    Settlement settle(List<UUID> shipmentIds) {
        Set<UUID> ids = new HashSet<>(shipmentIds);
        if (ids.isEmpty() || ids.size() > MAX_SETTLE) {
            throw ApiException.invalidField("shipmentIds", "field.invalid_value");
        }
        List<Shipment> locked = shipments.findLocked(ids);
        if (locked.size() != ids.size()) {
            throw ApiException.notFound();
        }
        Set<String> couriers = locked.stream().map(s -> s.getCourier().toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
        if (couriers.size() > 1) {
            throw ApiException.invalidField("shipmentIds", "field.settle_one_courier");
        }
        List<UUID> notSettleable = locked.stream()
                .filter(s -> !settleable(s))
                .map(Shipment::getId).sorted().toList();
        if (!notSettleable.isEmpty()) {
            throw new ApiException(ErrorCode.COD_NOT_SETTLEABLE).withDetail("shipmentIds", notSettleable);
        }
        return markSettled(locked);
    }

    private Settlement markSettled(List<Shipment> locked) {
        CurrentBusiness me = CurrentBusiness.require();
        Instant now = clock.instant();
        locked.forEach(s -> s.settled(now));
        shipments.flush();
        long amount = locked.stream().mapToLong(Shipment::getCodCollected).sum();
        String courier = locked.get(0).getCourier();
        audit.record(me.businessId(), me.userId(), "cod.settled", "business", me.businessId(), "courier", courier,
                "shipments", locked.size(), "amount", amount);
        return new Settlement(courier, locked.size(), amount, now);
    }
}
