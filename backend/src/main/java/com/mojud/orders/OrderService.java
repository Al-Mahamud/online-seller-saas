package com.mojud.orders;

import com.mojud.billing.Entitlements;
import com.mojud.billing.Metric;
import com.mojud.catalog.CatalogLimits;
import com.mojud.catalog.CatalogLookup;
import com.mojud.customers.CustomerDirectory;
import com.mojud.delivery.DeliveryCharges;
import com.mojud.delivery.GeoDirectory;
import com.mojud.shared.audit.AuditTrail;
import com.mojud.shared.error.ApiException;
import com.mojud.shared.error.ErrorCode;
import com.mojud.shared.idempotency.Idempotency;
import com.mojud.shared.paging.Cursor;
import com.mojud.shared.paging.CursorPage;
import com.mojud.shared.phone.PhoneNumber;
import com.mojud.shared.text.Text;
import com.mojud.shared.time.DhakaTime;
import com.mojud.tenancy.BusinessRole;
import com.mojud.tenancy.CurrentBusiness;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orders up to their confirmation (implementation-plan.md 7a): drafts with server-computed totals,
 * numbering, lists and edits. Status changes after DRAFT belong to OrderTransitions (7b).
 */
@Service
class OrderService {

    static final int MAX_ITEMS = 100;
    static final int MAX_NOTE = 1000;
    static final int MAX_ADDRESS = 500;
    static final int MAX_NAME = 100;

    private static final Instant END_OF_TIME = Instant.parse("9999-12-31T00:00:00Z");
    private static final Instant START_OF_TIME = Instant.parse("2000-01-01T00:00:00Z");
    private static final UUID LAST_ID = new UUID(-1L, -1L);

    /** {@code unitPrice} null: the variant's price. */
    record ItemInput(UUID variantId, int quantity, Long unitPrice) {
    }

    /** A customer by phone: the existing one with this number, else a new one with these details. */
    record CustomerInput(String name, String phone, String address, Integer districtId, Integer areaId) {
    }

    /** Where the parcel goes; each field left out is taken from the customer. */
    record DeliveryInput(String name, String phone, String address, Integer districtId, Integer areaId) {
    }

    /**
     * A new order: {@code customerId} or {@code customer}; {@code deliveryCharge} null takes the
     * delivery zone's charge for the address; money in poisha.
     */
    record Draft(UUID customerId, CustomerInput customer, DeliveryInput delivery, List<ItemInput> items, Long discount,
            Long deliveryCharge, Long advancePaid, PaymentMethod advanceMethod, String advanceReference, String note) {
    }

    /**
     * Changes to a draft; null fields stay. {@code items} replace all lines. A new delivery place
     * without a {@code deliveryCharge} takes the new place's zone charge. The advance is not edited
     * here: payments are recorded (8b), so the order's advance is always their sum.
     */
    record Changes(UUID customerId, CustomerInput customer, DeliveryInput delivery, List<ItemInput> items, Long discount,
            Long deliveryCharge, String note, Long version) {
    }

    private final OrderRepository orders;
    private final Entitlements entitlements;
    private final OrderItemRepository items;
    private final ShipmentRepository shipments;
    private final PaymentRepository payments;
    private final OrderReturns returns;
    private final OrderNumbers numbers;
    private final CatalogLookup catalog;
    private final CustomerDirectory customers;
    private final DeliveryCharges charges;
    private final GeoDirectory geo;
    private final Idempotency idempotency;
    private final OrderEvents events;
    private final OrderStock stock;
    private final AuditTrail audit;
    private final Clock clock;

    OrderService(OrderRepository orders, OrderItemRepository items, ShipmentRepository shipments, PaymentRepository payments,
            OrderReturns returns, OrderNumbers numbers, CatalogLookup catalog,
            CustomerDirectory customers, DeliveryCharges charges, GeoDirectory geo, Idempotency idempotency,
            OrderEvents events, OrderStock stock, AuditTrail audit, Clock clock, Entitlements entitlements) {
        this.entitlements = entitlements;
        this.orders = orders;
        this.items = items;
        this.shipments = shipments;
        this.payments = payments;
        this.returns = returns;
        this.numbers = numbers;
        this.catalog = catalog;
        this.customers = customers;
        this.charges = charges;
        this.geo = geo;
        this.idempotency = idempotency;
        this.events = events;
        this.stock = stock;
        this.audit = audit;
        this.clock = clock;
    }

    /** With an {@code Idempotency-Key}, a resent request returns the first answer and makes no second order. */
    @Transactional
    OrderViews.Detail create(Draft draft, String idempotencyKey) {
        return idempotency.run(idempotencyKey, draft, OrderViews.Detail.class, () -> {
            CurrentBusiness me = CurrentBusiness.require();
            CustomerDirectory.Snapshot customer = customer(draft.customerId(), draft.customer(), true);
            Order.Parties parties = parties(customer, draft.delivery(), null);
            List<Totals.Line> lines = lines(draft.items());
            long deliveryCharge = draft.deliveryCharge() != null ? charge(draft.deliveryCharge())
                    : charges.quote(parties.deliveryAreaId(), parties.deliveryDistrictId()).charge();
            Totals totals = Totals.of(lines, amount(draft.discount(), "discount"), deliveryCharge,
                    amount(draft.advancePaid(), "advancePaid"));
            // Counted once per order: a resend with the same key replays the answer and is not counted.
            entitlements.consume(Metric.ORDERS_PER_MONTH);
            OrderNumbers.Number number = numbers.next();
            Instant now = clock.instant();
            Order order = orders.save(new Order(number.value(), number.label(), parties, totals, note(draft.note()),
                    me.userId(), now));
            saveLines(order.getId(), lines);
            if (totals.advancePaid() > 0) {
                payments.save(new Payment(order.getId(), draft.advanceMethod() == null ? PaymentMethod.OTHER : draft.advanceMethod(),
                        totals.advancePaid(), reference(draft.advanceReference()), now, me.userId(), now));
            }
            // The event is plain SQL: the order must be written first.
            orders.flush();
            events.record(order.getId(), null, OrderStatus.DRAFT, me.userId(), "", now);
            audit.record(me.businessId(), me.userId(), "order.created", "order", order.getId(),
                    "number", number.label(), "total", totals.total());
            return detail(order);
        });
    }

    /**
     * Edits a DRAFT or CONFIRMED order. In a confirmed order only the difference in items moves
     * stock (more reserved or taken, the rest given back), the customer stays, and the delivery
     * details stay complete. Packed orders are not edited (backend-plan.md §6).
     */
    @Transactional
    OrderViews.Detail update(UUID id, Changes changes) {
        CurrentBusiness me = CurrentBusiness.require();
        Order order = orders.findLocked(id).orElseThrow(ApiException::notFound);
        if (changes.version() != null && !Objects.equals(changes.version(), order.getVersion())) {
            throw new ApiException(ErrorCode.CONCURRENT_MODIFICATION);
        }
        if (order.getStatus() != OrderStatus.DRAFT && order.getStatus() != OrderStatus.CONFIRMED) {
            throw new ApiException(ErrorCode.ORDER_NOT_EDITABLE);
        }
        boolean confirmed = order.getStatus() == OrderStatus.CONFIRMED;
        Order.Parties before = order.parties();
        boolean customerChanged = changes.customerId() != null || changes.customer() != null;
        if (confirmed && customerChanged) {
            throw ApiException.invalidField("customer", "field.customer_fixed");
        }
        CustomerDirectory.Snapshot customer = customerChanged
                ? customer(changes.customerId(), changes.customer(), true)
                : customers.find(before.customerId()).orElseThrow();
        Order.Parties parties = customerChanged || changes.delivery() != null
                ? parties(customer, changes.delivery(), customerChanged ? null : before)
                : before;
        if (confirmed) {
            requireDeliveryDetails(parties);
        }
        List<Totals.Line> current = currentLines(id);
        List<Totals.Line> lines = changes.items() != null ? lines(changes.items()) : current;
        boolean placeChanged = !Objects.equals(parties.deliveryAreaId(), before.deliveryAreaId())
                || !Objects.equals(parties.deliveryDistrictId(), before.deliveryDistrictId());
        long deliveryCharge = changes.deliveryCharge() != null ? charge(changes.deliveryCharge())
                : placeChanged ? charges.quote(parties.deliveryAreaId(), parties.deliveryDistrictId()).charge()
                : order.getDeliveryCharge();
        Totals totals = Totals.of(lines,
                changes.discount() != null ? amount(changes.discount(), "discount") : order.getDiscount(),
                deliveryCharge, order.getAdvancePaid());
        String note = changes.note() != null ? note(changes.note()) : order.getNote();
        order.change(parties, totals, note, clock.instant());
        if (changes.items() != null) {
            stock.rebalance(id, current, lines, order.getStockState());
            items.deleteByOrder(id);
            saveLines(id, lines);
        }
        orders.flush();
        audit.record(me.businessId(), me.userId(), "order.updated", "order", id, "total", totals.total());
        return detail(order);
    }

    @Transactional(readOnly = true)
    OrderViews.Detail get(UUID id) {
        return detail(find(id));
    }

    /**
     * Newest first. {@code from}/{@code to}: days in Dhaka, both included; {@code phone}: the
     * customer's or the delivery number, written any way; {@code number}: 1001, "#1001" or "RK-1001".
     */
    @Transactional(readOnly = true)
    CursorPage<OrderViews.Summary> list(List<OrderStatus> statuses, LocalDate from, LocalDate to, String phone,
            String number, String cursor, Integer limit) {
        Filter filter = Filter.of(from, to, phone, number);
        if (filter == null) {
            return new CursorPage<>(List.of(), null);
        }
        int pageSize = CursorPage.limit(limit);
        Cursor after = Cursor.decode(cursor);
        List<Order> page = orders.findPage(statuses == null || statuses.isEmpty() ? Arrays.asList(OrderStatus.values()) : statuses,
                filter.from(), filter.to(), filter.phone(), filter.number(), after == null ? END_OF_TIME : after.sortKeyAsInstant(),
                after == null ? LAST_ID : after.id(), Limit.of(pageSize + 1));
        CursorPage<Order> paged = CursorPage.of(page, pageSize, o -> new Cursor(o.getCreatedAt().toString(), o.getId()));
        Map<UUID, Integer> counts = new HashMap<>();
        if (!paged.items().isEmpty()) {
            items.countByOrders(paged.items().stream().map(Order::getId).toList())
                    .forEach(row -> counts.put((UUID) row[0], ((Number) row[1]).intValue()));
        }
        return new CursorPage<>(paged.items().stream().map(o -> OrderViews.summary(o, counts.getOrDefault(o.getId(), 0)))
                .toList(), paged.nextCursor());
    }

    /** How many orders each status has, for the same filters as the list: every status, none left out. */
    @Transactional(readOnly = true)
    Map<OrderStatus, Integer> counts(LocalDate from, LocalDate to, String phone, String number) {
        Map<OrderStatus, Integer> counts = new EnumMap<>(OrderStatus.class);
        Arrays.stream(OrderStatus.values()).forEach(status -> counts.put(status, 0));
        Filter filter = Filter.of(from, to, phone, number);
        if (filter != null) {
            orders.countByStatus(filter.from(), filter.to(), filter.phone(), filter.number())
                    .forEach(row -> counts.put((OrderStatus) row[0], ((Number) row[1]).intValue()));
        }
        return counts;
    }

    /** The list's filters as the queries take them. */
    private record Filter(Instant from, Instant to, String phone, long number) {

        /** Null when nothing can match: a phone or a number that isn't one. */
        static Filter of(LocalDate from, LocalDate to, String phone, String number) {
            String e164 = "";
            if (phone != null && !phone.isBlank()) {
                e164 = PhoneNumber.tryParse(phone).map(PhoneNumber::e164).orElse(null);
                if (e164 == null) {
                    return null;
                }
            }
            long wanted = 0;
            if (number != null && !number.isBlank()) {
                String digits = number.replaceAll("\\D", "");
                if (digits.isEmpty() || digits.length() > 15) {
                    return null;
                }
                wanted = Long.parseLong(digits);
            }
            return new Filter(from == null ? START_OF_TIME : DhakaTime.startOf(from),
                    to == null ? END_OF_TIME : DhakaTime.startOf(to.plusDays(1)), e164, wanted);
        }
    }

    private CustomerDirectory.Snapshot customer(UUID customerId, CustomerInput input, boolean required) {
        if ((customerId == null) == (input == null)) {
            if (!required && customerId == null) {
                return null;
            }
            throw ApiException.invalidField("customer", "field.customer_one_of");
        }
        if (customerId != null) {
            return customers.find(customerId).orElseThrow(() -> ApiException.invalidField("customerId", "field.invalid_value"));
        }
        return customers.findOrCreate(input.name(), input.phone(), input.address(), input.districtId(), input.areaId());
    }

    /**
     * Where the parcel goes: what is given, else (when {@code before} is null) the customer's own
     * details, else what the order had.
     */
    private Order.Parties parties(CustomerDirectory.Snapshot customer, DeliveryInput given, Order.Parties before) {
        DeliveryInput delivery = given == null ? new DeliveryInput(null, null, null, null, null) : given;
        String name = delivery.name() != null ? Text.cleanName(delivery.name(), "delivery.name", MAX_NAME)
                : before != null ? before.deliveryName() : customer.name();
        String phone = delivery.phone() != null ? PhoneNumber.fromInput(delivery.phone(), "delivery.phone").e164()
                : before != null ? before.deliveryPhone() : customer.phone();
        String address = delivery.address() != null ? text(delivery.address(), "delivery.address", MAX_ADDRESS)
                : before != null ? before.deliveryAddress() : Objects.requireNonNullElse(customer.address(), "");
        Integer districtId;
        Integer areaId;
        if (delivery.areaId() != null || delivery.districtId() != null) {
            areaId = delivery.areaId();
            districtId = delivery.districtId();
            if (areaId != null) {
                GeoDirectory.Area area = geo.area(areaId)
                        .orElseThrow(() -> ApiException.invalidField("delivery.areaId", "field.invalid_value"));
                if (districtId != null && districtId != area.districtId()) {
                    throw ApiException.invalidField("delivery.areaId", "field.area_not_in_district");
                }
                districtId = area.districtId();
            } else if (geo.district(districtId).isEmpty()) {
                throw ApiException.invalidField("delivery.districtId", "field.invalid_value");
            }
        } else if (before != null) {
            districtId = before.deliveryDistrictId();
            areaId = before.deliveryAreaId();
        } else {
            districtId = customer.districtId();
            areaId = customer.areaId();
        }
        return new Order.Parties(customer.id(), customer.name(), customer.phone(), name, phone, address, districtId, areaId);
    }

    /** Lines priced from the catalog (or the price given), checked like the forms. */
    private List<Totals.Line> lines(List<ItemInput> inputs) {
        if (inputs == null || inputs.isEmpty() || inputs.size() > MAX_ITEMS) {
            throw ApiException.invalidField("items", "field.order_items_count");
        }
        Set<UUID> seen = new HashSet<>();
        for (ItemInput input : inputs) {
            if (!seen.add(input.variantId())) {
                throw ApiException.invalidField("items", "field.variant_repeated");
            }
        }
        Map<UUID, CatalogLookup.VariantInfo> variants = catalog.variants(seen);
        List<Totals.Line> lines = new ArrayList<>();
        for (ItemInput input : inputs) {
            CatalogLookup.VariantInfo variant = variants.get(input.variantId());
            if (variant == null || !variant.sellable()) {
                throw ApiException.invalidField("items", "field.variant_unavailable");
            }
            long price = input.unitPrice() != null ? input.unitPrice() : variant.price();
            if (price < 0 || price > CatalogLimits.MAX_POISHA) {
                throw ApiException.invalidField("items", "field.invalid_value");
            }
            lines.add(new Totals.Line(variant.variantId(), variant.productName(), variant.label(), variant.sku(),
                    input.quantity(), price, variant.costPrice()));
        }
        return lines;
    }

    /** A confirmed order must say where it goes: an address and at least a district. */
    static void requireDeliveryDetails(Order.Parties parties) {
        if (parties.deliveryAddress().isBlank()) {
            throw ApiException.invalidField("delivery.address", "field.needed_to_confirm");
        }
        if (parties.deliveryDistrictId() == null) {
            throw ApiException.invalidField("delivery.districtId", "field.needed_to_confirm");
        }
    }

    List<Totals.Line> currentLines(UUID orderId) {
        return items.findByOrder(orderId).stream().map(item -> new Totals.Line(item.getVariantId(), item.getProductName(),
                item.getVariantLabel(), item.getSku(), item.getQuantity(), item.getUnitPrice(), item.getUnitCost())).toList();
    }

    private void saveLines(UUID orderId, List<Totals.Line> lines) {
        for (int i = 0; i < lines.size(); i++) {
            items.save(new OrderItem(orderId, lines.get(i), i));
        }
    }

    private static long amount(Long value, String field) {
        if (value == null) {
            return 0;
        }
        if (value < 0 || value > Totals.MAX_TOTAL) {
            throw ApiException.invalidField(field, "field.invalid_value");
        }
        return value;
    }

    private static long charge(long value) {
        return amount(value, "deliveryCharge");
    }

    static String reference(String reference) {
        String cleaned = reference == null ? "" : reference.strip();
        if (cleaned.length() > 100) {
            throw ApiException.invalidField("reference", "field.too_long");
        }
        return cleaned;
    }

    private static String note(String note) {
        return note == null ? "" : text(note, "note", MAX_NOTE);
    }

    private static String text(String input, String field, int max) {
        String cleaned = input.strip();
        if (cleaned.length() > max) {
            throw ApiException.invalidField(field, "field.too_long");
        }
        return cleaned;
    }

    private Order find(UUID id) {
        return orders.findById(id).orElseThrow(ApiException::notFound);
    }

    OrderViews.Detail detail(Order order) {
        boolean showCost = CurrentBusiness.require().isAtLeast(BusinessRole.MANAGER);
        List<OrderItem> lines = items.findByOrder(order.getId());
        return OrderViews.detail(order, lines, shipments.findByOrder(order.getId()), payments.findByOrder(order.getId()),
                returns.forOrder(order.getId(), lines), events.forOrder(order.getId()), geo, showCost);
    }
}
