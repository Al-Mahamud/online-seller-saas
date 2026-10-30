package com.mojud.delivery;

import com.mojud.shared.audit.AuditTrail;
import com.mojud.shared.db.AdvisoryLocks;
import com.mojud.shared.error.ApiException;
import com.mojud.shared.id.Ids;
import com.mojud.shared.tenant.TenantScope;
import com.mojud.shared.text.Text;
import com.mojud.tenancy.BusinessCreated;
import com.mojud.tenancy.CurrentBusiness;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.context.MessageSource;
import org.springframework.context.event.EventListener;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * A business's delivery zones (implementation-plan.md 6b): read as a list, changed as a whole set
 * (the form shows them all, and moving a district from one zone to another is one change).
 */
@Service
class DeliveryZones {

    static final int MAX_ZONES = 20;
    static final int MAX_NAME = 50;
    /** ৳100,000, in poisha. */
    static final long MAX_CHARGE = 10_000_000L;

    record Place(int id, String nameEn, String nameBn) {
    }

    /** {@code charge} in poisha. */
    record Zone(UUID id, String name, long charge, boolean isDefault, List<Place> districts, List<Place> areas) {
    }

    /** {@code id}: an existing zone keeps its id (null for a new one). */
    record ZoneInput(UUID id, String name, long charge, boolean isDefault, List<Integer> districtIds, List<Integer> areaIds) {
    }

    private record Row(UUID id, String name, long charge, boolean isDefault) {
    }

    private record Link(UUID zoneId, int placeId) {
    }

    private final JdbcClient jdbc;
    private final JdbcTemplate batch;
    private final GeoDirectory geo;
    private final AdvisoryLocks locks;
    private final AuditTrail audit;
    private final Clock clock;
    private final MessageSource messages;

    DeliveryZones(DataSource dataSource, GeoDirectory geo, AdvisoryLocks locks, AuditTrail audit, Clock clock,
            MessageSource messages) {
        this.messages = messages;
        this.jdbc = JdbcClient.create(dataSource);
        this.batch = new JdbcTemplate(dataSource);
        this.geo = geo;
        this.locks = locks;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * Every new business: Inside Dhaka (Dhaka district) ৳70, and Outside Dhaka ৳130 for everything
     * else, named in the language of whoever creates it.
     */
    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    void on(BusinessCreated event) {
        write(event.businessId(), List.of(
                new ZoneInput(null, defaultName("delivery.zone.inside_dhaka"), 7_000, false, List.of(GeoIds.DHAKA_DISTRICT), List.of()),
                new ZoneInput(null, defaultName("delivery.zone.outside_dhaka"), 13_000, true, List.of(), List.of())), Map.of());
    }

    private String defaultName(String key) {
        return messages.getMessage(key, null, LocaleContextHolder.getLocale());
    }

    /** In the order they were given; the default zone is the fallback for everywhere not listed. */
    @Transactional(readOnly = true)
    List<Zone> list() {
        UUID business = TenantScope.current().orElseThrow();
        List<Row> rows = jdbc.sql("select id, name, charge, is_default from delivery_zones where business_id = ? order by position")
                .param(business)
                .query((rs, n) -> new Row(rs.getObject(1, UUID.class), rs.getString(2), rs.getLong(3), rs.getBoolean(4)))
                .list();
        Map<UUID, List<Place>> districts = new HashMap<>();
        links("select zone_id, district_id from delivery_zone_districts where business_id = ?", business)
                .forEach(link -> geo.district(link.placeId()).ifPresent(d -> districts
                        .computeIfAbsent(link.zoneId(), key -> new ArrayList<>()).add(new Place(d.id(), d.nameEn(), d.nameBn()))));
        Map<UUID, List<Place>> areas = new HashMap<>();
        links("select zone_id, area_id from delivery_zone_areas where business_id = ?", business)
                .forEach(link -> geo.area(link.placeId()).ifPresent(a -> areas
                        .computeIfAbsent(link.zoneId(), key -> new ArrayList<>()).add(new Place(a.id(), a.nameEn(), a.nameBn()))));
        return rows.stream().map(row -> new Zone(row.id(), row.name(), row.charge(), row.isDefault(),
                sorted(districts.getOrDefault(row.id(), List.of())), sorted(areas.getOrDefault(row.id(), List.of()))))
                .toList();
    }

    /**
     * Replaces the business's zones. Exactly one is the default; names differ (ignoring case); each
     * district and each area is in at most one zone. A zone sent with its id keeps it.
     */
    @Transactional
    List<Zone> replace(List<ZoneInput> zones) {
        UUID business = CurrentBusiness.require().businessId();
        locks.lockForTransaction("delivery-zones:" + business);
        Map<UUID, Instant> created = new HashMap<>();
        jdbc.sql("select id, created_at from delivery_zones where business_id = ?").param(business)
                .query((rs, n) -> Map.entry(rs.getObject(1, UUID.class), rs.getTimestamp(2).toInstant())).list()
                .forEach(entry -> created.put(entry.getKey(), entry.getValue()));
        List<ZoneInput> cleaned = validate(zones, created.keySet());
        jdbc.sql("delete from delivery_zones where business_id = ?").param(business).update();
        write(business, cleaned, created);
        audit.record(business, CurrentBusiness.require().userId(), "delivery_zones.changed", "business", business,
                "zones", cleaned.size());
        return list();
    }

    private List<ZoneInput> validate(List<ZoneInput> zones, Set<UUID> existing) {
        if (zones.isEmpty() || zones.size() > MAX_ZONES) {
            throw ApiException.invalidField("zones", "field.zones_count");
        }
        if (zones.stream().filter(ZoneInput::isDefault).count() != 1) {
            throw ApiException.invalidField("zones", "field.zones_one_default");
        }
        Set<String> names = new HashSet<>();
        Set<UUID> ids = new HashSet<>();
        Set<Integer> districts = new HashSet<>();
        Set<Integer> areas = new HashSet<>();
        List<ZoneInput> cleaned = new ArrayList<>();
        for (ZoneInput zone : zones) {
            String name = Text.cleanName(zone.name(), "zones", MAX_NAME);
            if (!names.add(name.toLowerCase(Locale.ROOT))) {
                throw ApiException.invalidField("zones", "field.zones_name_twice");
            }
            if (zone.charge() < 0 || zone.charge() > MAX_CHARGE) {
                throw ApiException.invalidField("zones", "field.invalid_value");
            }
            UUID id = zone.id();
            if (id != null && (!existing.contains(id) || !ids.add(id))) {
                // Not one of this business's zones (or given twice): treated like any unknown id.
                throw ApiException.invalidField("zones", "field.invalid_value");
            }
            List<Integer> districtIds = zone.districtIds() == null ? List.of() : zone.districtIds();
            List<Integer> areaIds = zone.areaIds() == null ? List.of() : zone.areaIds();
            for (Integer district : districtIds) {
                if (district == null || geo.district(district).isEmpty()) {
                    throw ApiException.invalidField("zones", "field.invalid_value");
                }
                if (!districts.add(district)) {
                    throw ApiException.invalidField("zones", "field.zones_place_twice");
                }
            }
            for (Integer area : areaIds) {
                if (area == null || geo.area(area).isEmpty()) {
                    throw ApiException.invalidField("zones", "field.invalid_value");
                }
                if (!areas.add(area)) {
                    throw ApiException.invalidField("zones", "field.zones_place_twice");
                }
            }
            cleaned.add(new ZoneInput(id, name, zone.charge(), zone.isDefault(), List.copyOf(districtIds), List.copyOf(areaIds)));
        }
        return cleaned;
    }

    private void write(UUID business, List<ZoneInput> zones, Map<UUID, Instant> created) {
        Instant now = clock.instant();
        Map<ZoneInput, UUID> ids = new LinkedHashMap<>();
        for (int position = 0; position < zones.size(); position++) {
            ZoneInput zone = zones.get(position);
            UUID id = zone.id() != null ? zone.id() : Ids.newId();
            ids.put(zone, id);
            jdbc.sql("""
                            insert into delivery_zones (id, business_id, name, charge, is_default, position, created_at, updated_at)
                            values (?, ?, ?, ?, ?, ?, ?, ?)
                            """)
                    .params(id, business, zone.name(), zone.charge(), zone.isDefault(), position,
                            Timestamp.from(created.getOrDefault(id, now)), Timestamp.from(now))
                    .update();
        }
        List<Object[]> districtRows = new ArrayList<>();
        List<Object[]> areaRows = new ArrayList<>();
        ids.forEach((zone, id) -> {
            zone.districtIds().forEach(district -> districtRows.add(new Object[] {business, id, district}));
            zone.areaIds().forEach(area -> areaRows.add(new Object[] {business, id, area}));
        });
        batch.batchUpdate("insert into delivery_zone_districts (business_id, zone_id, district_id) values (?, ?, ?)", districtRows);
        batch.batchUpdate("insert into delivery_zone_areas (business_id, zone_id, area_id) values (?, ?, ?)", areaRows);
    }

    private List<Link> links(String sql, UUID business) {
        return jdbc.sql(sql).param(business).query((rs, n) -> new Link(rs.getObject(1, UUID.class), rs.getInt(2))).list();
    }

    private static List<Place> sorted(List<Place> places) {
        return places.stream().sorted((a, b) -> a.nameEn().compareTo(b.nameEn())).toList();
    }
}
