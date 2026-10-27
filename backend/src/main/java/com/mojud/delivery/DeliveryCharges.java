package com.mojud.delivery;

import com.mojud.shared.error.ApiException;
import com.mojud.shared.tenant.TenantScope;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The delivery charge for an address, in the business of the TenantScope (implementation-plan.md
 * 6b): the zone listing its area, else the zone listing its district, else the default zone. Used
 * by the order form and orders (Phase 7), and later by the AI's answers about delivery.
 */
@Component
public class DeliveryCharges {

    /** How the zone was found. */
    public enum MatchedBy {
        AREA, DISTRICT, DEFAULT
    }

    /** {@code charge} in poisha. */
    public record Quote(UUID zoneId, String zoneName, long charge, MatchedBy matchedBy) {
    }

    private final GeoDirectory geo;
    private final JdbcClient jdbc;

    DeliveryCharges(GeoDirectory geo, DataSource dataSource) {
        this.geo = geo;
        this.jdbc = JdbcClient.create(dataSource);
    }

    /**
     * Either id may be null; an area brings its own district.
     *
     * @throws ApiException a validation error for an unknown area or district, or one given with
     *         an area of another district
     */
    @Transactional(readOnly = true)
    public Quote quote(Integer areaId, Integer districtId) {
        Integer district = districtId;
        if (areaId != null) {
            GeoDirectory.Area area = geo.area(areaId).orElseThrow(() -> ApiException.invalidField("areaId", "field.invalid_value"));
            if (districtId != null && districtId != area.districtId()) {
                throw ApiException.invalidField("areaId", "field.area_not_in_district");
            }
            district = area.districtId();
        } else if (districtId != null && geo.district(districtId).isEmpty()) {
            throw ApiException.invalidField("districtId", "field.invalid_value");
        }
        UUID business = TenantScope.current().orElseThrow();
        if (areaId != null) {
            Quote byArea = zone("""
                    select z.id, z.name, z.charge from delivery_zone_areas a join delivery_zones z on z.id = a.zone_id
                    where a.business_id = ? and a.area_id = ?""", business, areaId, MatchedBy.AREA);
            if (byArea != null) {
                return byArea;
            }
        }
        if (district != null) {
            Quote byDistrict = zone("""
                    select z.id, z.name, z.charge from delivery_zone_districts d join delivery_zones z on z.id = d.zone_id
                    where d.business_id = ? and d.district_id = ?""", business, district, MatchedBy.DISTRICT);
            if (byDistrict != null) {
                return byDistrict;
            }
        }
        Quote fallback = jdbc.sql("select id, name, charge from delivery_zones where business_id = ? and is_default")
                .param(business)
                .query((rs, n) -> new Quote(rs.getObject(1, UUID.class), rs.getString(2), rs.getLong(3), MatchedBy.DEFAULT))
                .optional().orElse(null);
        if (fallback == null) {
            throw new IllegalStateException("Business " + business + " has no default delivery zone");
        }
        return fallback;
    }

    private Quote zone(String sql, UUID business, int id, MatchedBy matchedBy) {
        return jdbc.sql(sql).params(business, id)
                .query((rs, n) -> new Quote(rs.getObject(1, UUID.class), rs.getString(2), rs.getLong(3), matchedBy))
                .optional().orElse(null);
    }
}
