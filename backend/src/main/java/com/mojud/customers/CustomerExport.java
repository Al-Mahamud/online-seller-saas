package com.mojud.customers;

import com.mojud.delivery.GeoDirectory;
import com.mojud.shared.csv.CsvWriter;
import com.mojud.shared.phone.PhoneNumber;
import com.mojud.shared.tenant.TenantScope;
import com.mojud.shared.time.DhakaTime;
import java.io.OutputStream;
import java.sql.Timestamp;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import javax.sql.DataSource;
import org.springframework.context.MessageSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Customers as CSV (implementation-plan.md 9b), oldest first, read and written in batches. */
@Component
class CustomerExport {

    static final List<String> COLUMNS = List.of("name", "phone", "altPhone", "address", "area", "district", "orders",
            "lastOrder", "note", "since");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(DhakaTime.ZONE);

    private final JdbcTemplate jdbc;
    private final GeoDirectory geo;
    private final MessageSource messages;

    CustomerExport(DataSource dataSource, GeoDirectory geo, MessageSource messages) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.jdbc.setFetchSize(500);
        this.geo = geo;
        this.messages = messages;
    }

    @Transactional(readOnly = true)
    void write(OutputStream out, Locale locale) {
        boolean bangla = locale.getLanguage().equals("bn");
        try (CsvWriter csv = new CsvWriter(out)) {
            csv.row(COLUMNS.stream().map(c -> messages.getMessage("export.customers." + c, null, c, locale)).toList());
            jdbc.query("""
                            select name, phone, alt_phone, address, area_id, district_id, order_count, last_order_at, note, created_at
                            from customers where business_id = ? order by created_at, id
                            """,
                    rs -> {
                        Integer area = (Integer) rs.getObject(5);
                        Integer district = (Integer) rs.getObject(6);
                        Timestamp last = rs.getTimestamp(8);
                        csv.text(rs.getString(1))
                                .text(new PhoneNumber(rs.getString(2)).display())
                                .text(rs.getString(3) == null ? "" : new PhoneNumber(rs.getString(3)).display())
                                .text(rs.getString(4))
                                .text(area == null ? "" : geo.area(area).map(a -> bangla ? a.nameBn() : a.nameEn()).orElse(""))
                                .text(district == null ? "" : geo.district(district).map(d -> bangla ? d.nameBn() : d.nameEn()).orElse(""))
                                .number(rs.getInt(7))
                                .text(last == null ? "" : DAY.format(last.toInstant()))
                                .text(rs.getString(9))
                                .text(DAY.format(rs.getTimestamp(10).toInstant()))
                                .end();
                    },
                    TenantScope.current().orElseThrow());
        }
    }
}
