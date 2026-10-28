package com.mojud.orders;

import com.mojud.delivery.GeoDirectory;
import com.mojud.shared.csv.CsvWriter;
import com.mojud.shared.phone.PhoneNumber;
import com.mojud.shared.tenant.TenantScope;
import com.mojud.shared.time.DhakaTime;
import java.io.OutputStream;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import javax.sql.DataSource;
import org.springframework.context.MessageSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orders as CSV (implementation-plan.md 9b), one row per order, read from the database in batches
 * and written as they come, so a large export never sits in memory. Money in taka.
 */
@Component
class OrderExport {

    static final List<String> COLUMNS = List.of("number", "date", "status", "paymentStatus", "customer", "customerPhone",
            "deliveryName", "deliveryPhone", "address", "area", "district", "items", "subtotal", "discount", "deliveryCharge",
            "total", "advancePaid", "codAmount", "returnCredit", "amountPaid", "balanceDue", "courier", "trackingNo", "note");
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(DhakaTime.ZONE);

    private final JdbcTemplate jdbc;
    private final GeoDirectory geo;
    private final MessageSource messages;

    OrderExport(DataSource dataSource, GeoDirectory geo, MessageSource messages) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.jdbc.setFetchSize(500);
        this.geo = geo;
        this.messages = messages;
    }

    /** Orders made in [from, to] (Dhaka days), oldest first. */
    @Transactional(readOnly = true)
    void write(OutputStream out, LocalDate from, LocalDate to, Locale locale) {
        boolean bangla = locale.getLanguage().equals("bn");
        try (CsvWriter csv = new CsvWriter(out)) {
            csv.row(COLUMNS.stream().map(c -> messages.getMessage("export.orders." + c, null, c, locale)).toList());
            jdbc.query("""
                            select o.number_label, o.created_at, o.status, o.customer_name, o.customer_phone, o.delivery_name,
                                   o.delivery_phone, o.delivery_address, o.delivery_area_id, o.delivery_district_id,
                                   (select string_agg(i.product_name || case when i.variant_label <> '' then ' ' || i.variant_label else '' end
                                                      || ' x' || i.quantity, '; ' order by i.position)
                                    from order_items i where i.order_id = o.id),
                                   o.subtotal, o.discount, o.delivery_charge, o.total, o.advance_paid, o.cod_amount,
                                   o.return_credit, o.amount_paid, s.courier, s.tracking_no, o.note
                            from orders o
                            left join lateral (select courier, tracking_no from shipments
                                               where order_id = o.id order by shipped_at desc limit 1) s on true
                            where o.business_id = ? and o.created_at >= ? and o.created_at < ?
                            order by o.created_at, o.id
                            """,
                    rs -> {
                        long total = rs.getLong(15);
                        long credit = rs.getLong(18);
                        long paid = rs.getLong(19);
                        long owed = total - credit;
                        Integer area = (Integer) rs.getObject(9);
                        Integer district = (Integer) rs.getObject(10);
                        csv.text(rs.getString(1))
                                .text(WHEN.format(rs.getTimestamp(2).toInstant()))
                                .text(rs.getString(3))
                                .text(paid > owed ? "REFUND_DUE" : paid == owed ? "PAID" : paid == 0 ? "UNPAID" : "PARTIAL")
                                .text(rs.getString(4))
                                .text(new PhoneNumber(rs.getString(5)).display())
                                .text(rs.getString(6))
                                .text(new PhoneNumber(rs.getString(7)).display())
                                .text(rs.getString(8))
                                .text(area == null ? "" : geo.area(area).map(a -> bangla ? a.nameBn() : a.nameEn()).orElse(""))
                                .text(district == null ? "" : geo.district(district).map(d -> bangla ? d.nameBn() : d.nameEn()).orElse(""))
                                .text(rs.getString(11))
                                .taka(rs.getLong(12)).taka(rs.getLong(13)).taka(rs.getLong(14)).taka(total)
                                .taka(rs.getLong(16)).taka(rs.getLong(17)).taka(credit).taka(paid).taka(owed - paid)
                                .text(rs.getString(20)).text(rs.getString(21)).text(rs.getString(22))
                                .end();
                    },
                    TenantScope.current().orElseThrow(), Timestamp.from(DhakaTime.startOf(from)),
                    Timestamp.from(DhakaTime.startOf(to.plusDays(1))));
        }
    }
}
