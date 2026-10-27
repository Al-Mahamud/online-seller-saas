package com.mojud.customers;

import com.mojud.delivery.GeoDirectory;
import com.mojud.shared.db.Constraints;
import com.mojud.shared.error.ApiException;
import com.mojud.shared.error.ErrorCode;
import com.mojud.shared.paging.Cursor;
import com.mojud.shared.paging.CursorPage;
import com.mojud.shared.phone.PhoneNumber;
import com.mojud.shared.text.Text;
import com.mojud.tenancy.CurrentBusiness;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Customers of the selected business (implementation-plan.md 6a). A phone number is one customer:
 * every way of writing it ("01712-345678", "+8801712345678", Bangla digits) is the same number.
 */
@Service
class CustomerService implements CustomerDirectory {

    static final int MAX_NAME = 100;
    static final int MAX_ADDRESS = 500;
    static final int MAX_NOTE = 1000;
    static final int SEARCH_RESULTS = 50;

    private static final Instant END_OF_TIME = Instant.parse("9999-12-31T00:00:00Z");
    private static final UUID LAST_ID = new UUID(-1L, -1L);
    /** Search text that is a phone number, or part of one: digits and the usual separators only. */
    private static final Pattern PHONE_LIKE = Pattern.compile("[+\\d০-৯\\s\\-.()]+");

    record GeoRef(int id, String nameEn, String nameBn) {
    }

    record View(UUID id, String name, String phone, String altPhone, String address, GeoRef district, GeoRef area,
            String note, int orderCount, Instant lastOrderAt, Instant createdAt, Instant updatedAt, Long version) {
    }

    /** {@code districtId} may be left out when {@code areaId} is given: the area's district is used. */
    record Details(String name, String phone, String altPhone, String address, Integer districtId, Integer areaId,
            String note) {
    }

    /**
     * Null fields stay as they are; an empty {@code altPhone}, {@code address} or {@code note}
     * clears it; {@code clearLocation} clears the district and area.
     */
    record Changes(String name, String phone, String altPhone, String address, Integer districtId, Integer areaId,
            boolean clearLocation, String note, Long version) {
    }

    private final CustomerRepository customers;
    private final GeoDirectory geo;
    private final TransactionTemplate transactions;
    private final Clock clock;

    CustomerService(CustomerRepository customers, GeoDirectory geo, TransactionTemplate transactions, Clock clock) {
        this.customers = customers;
        this.geo = geo;
        this.transactions = transactions;
        this.clock = clock;
    }

    /**
     * Newest first; with {@code q}, the best matches by phone (any part of the number, written any
     * way) or name (part of it, or close to it), up to 50 and not paged.
     */
    @Transactional(readOnly = true)
    CursorPage<View> list(String q, String cursor, Integer limit) {
        if (q != null && !q.isBlank()) {
            String query = Text.cleanName(q, "q", MAX_NAME);
            String digits = PHONE_LIKE.matcher(query).matches() ? digits(query) : "";
            String pattern = "%" + query.toLowerCase(Locale.ROOT).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
            return new CursorPage<>(customers.search(CurrentBusiness.require().businessId(), query, pattern,
                    digits.length() >= 3 ? digits : "", SEARCH_RESULTS).stream().map(this::view).toList(), null);
        }
        int pageSize = CursorPage.limit(limit);
        Cursor after = Cursor.decode(cursor);
        List<Customer> page = customers.findPage(after == null ? END_OF_TIME : after.sortKeyAsInstant(),
                after == null ? LAST_ID : after.id(), Limit.of(pageSize + 1));
        CursorPage<Customer> paged = CursorPage.of(page, pageSize, c -> new Cursor(c.getCreatedAt().toString(), c.getId()));
        return new CursorPage<>(paged.items().stream().map(this::view).toList(), paged.nextCursor());
    }

    /** The customer with this phone number, written any way; empty when none (or not a number). */
    @Transactional(readOnly = true)
    Optional<View> byPhone(String phone) {
        return PhoneNumber.tryParse(phone).flatMap(number -> customers.findByPhone(number.e164())).map(this::view);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Snapshot> find(UUID id) {
        return customers.findById(id).map(CustomerService::snapshot);
    }

    @Override
    @Transactional
    public Snapshot findOrCreate(String name, String phone, String address, Integer districtId, Integer areaId) {
        String e164 = PhoneNumber.fromInput(phone, "phone").e164();
        Optional<Customer> existing = customers.findByPhone(e164);
        if (existing.isPresent()) {
            return snapshot(existing.get());
        }
        View created = create(new Details(name, e164, null, address, districtId, areaId, null));
        return find(created.id()).orElseThrow();
    }

    private static Snapshot snapshot(Customer customer) {
        return new Snapshot(customer.getId(), customer.getName(), customer.getPhone(), customer.getAddress(),
                customer.getDistrictId(), customer.getAreaId());
    }

    @Transactional(readOnly = true)
    View get(UUID id) {
        return view(load(id));
    }

    /** @throws ApiException {@code customer.phone_taken} with the existing {@code customerId} */
    View create(Details details) {
        String name = Text.cleanName(details.name(), "name", MAX_NAME);
        String phone = PhoneNumber.fromInput(details.phone(), "phone").e164();
        String altPhone = altPhone(details.altPhone());
        String address = text(details.address(), "address", MAX_ADDRESS);
        String note = text(details.note(), "note", MAX_NOTE);
        Location location = location(details.districtId(), details.areaId());
        return saving(() -> {
            requirePhoneFree(phone, null);
            Customer customer = new Customer(name, phone, clock.instant());
            customer.changeAltPhone(altPhone);
            customer.changeAddress(address == null ? "" : address);
            customer.changeNote(note == null ? "" : note);
            customer.relocate(location.districtId(), location.areaId());
            return view(customers.saveAndFlush(customer));
        });
    }

    View update(UUID id, Changes changes) {
        String name = changes.name() == null ? null : Text.cleanName(changes.name(), "name", MAX_NAME);
        String phone = changes.phone() == null ? null : PhoneNumber.fromInput(changes.phone(), "phone").e164();
        String altPhone = changes.altPhone() == null ? null : altPhone(changes.altPhone());
        String address = text(changes.address(), "address", MAX_ADDRESS);
        String note = text(changes.note(), "note", MAX_NOTE);
        return saving(() -> {
            Customer customer = load(id);
            if (changes.version() != null && !Objects.equals(changes.version(), customer.getVersion())) {
                throw new ApiException(ErrorCode.CONCURRENT_MODIFICATION);
            }
            if (name != null) {
                customer.rename(name);
            }
            if (phone != null && !phone.equals(customer.getPhone())) {
                requirePhoneFree(phone, id);
                customer.changePhone(phone);
            }
            if (changes.altPhone() != null) {
                customer.changeAltPhone(altPhone);
            }
            if (address != null) {
                customer.changeAddress(address);
            }
            if (note != null) {
                customer.changeNote(note);
            }
            if (changes.clearLocation()) {
                customer.relocate(null, null);
            } else if (changes.areaId() != null) {
                Location location = location(changes.districtId(), changes.areaId());
                customer.relocate(location.districtId(), location.areaId());
            } else if (changes.districtId() != null) {
                // A new district keeps the area only if the area is in it.
                Location location = location(changes.districtId(), null);
                Integer area = Objects.equals(location.districtId(), customer.getDistrictId()) ? customer.getAreaId() : null;
                customer.relocate(location.districtId(), area);
            }
            customer.touch(clock.instant());
            return view(customers.saveAndFlush(customer));
        });
    }

    private record Location(Integer districtId, Integer areaId) {
    }

    /** Checks the ids; an area without a district brings its own. */
    private Location location(Integer districtId, Integer areaId) {
        if (areaId != null) {
            GeoDirectory.Area area = geo.area(areaId).orElseThrow(() -> ApiException.invalidField("areaId", "field.invalid_value"));
            if (districtId != null && districtId != area.districtId()) {
                throw ApiException.invalidField("areaId", "field.area_not_in_district");
            }
            return new Location(area.districtId(), area.id());
        }
        if (districtId != null) {
            geo.district(districtId).orElseThrow(() -> ApiException.invalidField("districtId", "field.invalid_value"));
        }
        return new Location(districtId, null);
    }

    private void requirePhoneFree(String phone, UUID self) {
        customers.findByPhone(phone).filter(other -> !other.getId().equals(self)).ifPresent(other -> {
            throw new ApiException(ErrorCode.CUSTOMER_PHONE_TAKEN).withDetail("customerId", other.getId());
        });
    }

    private static String altPhone(String input) {
        return input == null || input.isBlank() ? null : PhoneNumber.fromInput(input, "altPhone").e164();
    }

    /** Trimmed; null stays null. */
    private static String text(String input, String field, int max) {
        if (input == null) {
            return null;
        }
        String cleaned = input.strip();
        if (cleaned.length() > max) {
            throw ApiException.invalidField(field, "field.too_long");
        }
        return cleaned;
    }

    private static String digits(String text) {
        StringBuilder digits = new StringBuilder();
        for (char c : text.toCharArray()) {
            if (c >= '0' && c <= '9') {
                digits.append(c);
            } else if (c >= '০' && c <= '৯') {
                digits.append((char) ('0' + (c - '০')));
            }
        }
        return digits.toString();
    }

    private <T> T saving(Supplier<T> work) {
        // The unique index decides a race between two new customers with one number.
        return Constraints.translating(transactions, Map.of("uq_customers_phone", ErrorCode.CUSTOMER_PHONE_TAKEN), work);
    }

    private Customer load(UUID id) {
        return customers.findById(id).orElseThrow(ApiException::notFound);
    }

    private View view(Customer customer) {
        GeoRef district = customer.getDistrictId() == null ? null : geo.district(customer.getDistrictId())
                .map(d -> new GeoRef(d.id(), d.nameEn(), d.nameBn())).orElse(null);
        GeoRef area = customer.getAreaId() == null ? null : geo.area(customer.getAreaId())
                .map(a -> new GeoRef(a.id(), a.nameEn(), a.nameBn())).orElse(null);
        return new View(customer.getId(), customer.getName(), customer.getPhone(), customer.getAltPhone(),
                customer.getAddress(), district, area, customer.getNote(), customer.getOrderCount(),
                customer.getLastOrderAt(), customer.getCreatedAt(), customer.getUpdatedAt(), customer.getVersion());
    }
}
