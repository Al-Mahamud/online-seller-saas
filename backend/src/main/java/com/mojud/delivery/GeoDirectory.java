package com.mojud.delivery;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Bangladesh's divisions, districts and areas (V18). Fixed reference data, the same for every
 * business, so it is read once and kept in memory.
 */
@Component
public class GeoDirectory {

    public record Division(int id, String nameEn, String nameBn) {
    }

    public record District(int id, int divisionId, String nameEn, String nameBn) {
    }

    /** {@code kind}: UPAZILA, or METRO_THANA for a Dhaka city area. */
    public record Area(int id, int districtId, String kind, String nameEn, String nameBn) {
    }

    private record Data(List<Division> divisions, List<District> districts, Map<Integer, District> districtsById,
            Map<Integer, Area> areasById, Map<Integer, List<Area>> areasByDistrict) {
    }

    private final JdbcClient jdbc;
    private volatile Data data;

    GeoDirectory(DataSource dataSource) {
        this.jdbc = JdbcClient.create(dataSource);
    }

    public List<Division> divisions() {
        return data().divisions();
    }

    /** By English name. */
    public List<District> districts() {
        return data().districts();
    }

    public Optional<District> district(int id) {
        return Optional.ofNullable(data().districtsById().get(id));
    }

    public Optional<Area> area(int id) {
        return Optional.ofNullable(data().areasById().get(id));
    }

    /** By English name; empty for an unknown district. */
    public List<Area> areas(int districtId) {
        return data().areasByDistrict().getOrDefault(districtId, List.of());
    }

    private Data data() {
        Data loaded = data;
        if (loaded == null) {
            synchronized (this) {
                if (data == null) {
                    data = load();
                }
                loaded = data;
            }
        }
        return loaded;
    }

    private Data load() {
        List<Division> divisions = jdbc.sql("select id, name_en, name_bn from divisions order by name_en")
                .query((rs, n) -> new Division(rs.getInt(1), rs.getString(2), rs.getString(3))).list();
        List<District> districts = jdbc.sql("select id, division_id, name_en, name_bn from districts order by name_en")
                .query((rs, n) -> new District(rs.getInt(1), rs.getInt(2), rs.getString(3), rs.getString(4))).list();
        List<Area> areas = jdbc.sql("select id, district_id, kind, name_en, name_bn from areas order by name_en")
                .query((rs, n) -> new Area(rs.getInt(1), rs.getInt(2), rs.getString(3), rs.getString(4), rs.getString(5)))
                .list();
        return new Data(List.copyOf(divisions), List.copyOf(districts),
                districts.stream().collect(Collectors.toUnmodifiableMap(District::id, Function.identity())),
                areas.stream().collect(Collectors.toUnmodifiableMap(Area::id, Function.identity())),
                areas.stream().sorted(Comparator.comparing(Area::nameEn))
                        .collect(Collectors.groupingBy(Area::districtId, Collectors.toUnmodifiableList())));
    }
}
