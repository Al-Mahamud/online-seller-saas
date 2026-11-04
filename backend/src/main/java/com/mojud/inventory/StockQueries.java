package com.mojud.inventory;

import com.mojud.shared.paging.PageQuery;
import com.mojud.shared.paging.PagedResult;
import com.mojud.shared.tenant.TenantScope;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Reading stock without changing it, for other modules (product pages, lists). */
@Component
public class StockQueries {

    private final JdbcClient jdbc;

    StockQueries(DataSource dataSource) {
        this.jdbc = JdbcClient.create(dataSource);
    }

    /**
     * A variant's stock history, newest first. The caller has checked the variant exists in this
     * business; for any other id the history is simply empty.
     */
    public PagedResult<StockHistory> history(UUID variantId, PageQuery paging) {
        UUID business = TenantScope.current().orElseThrow();
        List<StockHistory> rows = jdbc.sql("""
                        select id, created_at, type, on_hand_delta, reserved_delta, on_hand_after, reserved_after,
                               reference_type, reference_id, note, user_id
                        from stock_movements
                        where business_id = :business and variant_id = :variant
                        order by created_at desc, id desc
                        limit :limit offset :offset
                        """)
                .param("business", business)
                .param("variant", variantId)
                .param("limit", paging.limit())
                .param("offset", paging.offset())
                .query((rs, n) -> new StockHistory(rs.getObject(1, UUID.class), rs.getTimestamp(2).toInstant(),
                        MovementType.valueOf(rs.getString(3)), rs.getInt(4), rs.getInt(5), rs.getInt(6), rs.getInt(7),
                        rs.getString(8), rs.getObject(9, UUID.class), rs.getString(10), rs.getObject(11, UUID.class)))
                .list();
        long total = jdbc.sql("select count(*) from stock_movements where business_id = :business and variant_id = :variant")
                .param("business", business).param("variant", variantId).query(Long.class).single();
        return PagedResult.of(rows, paging, total);
    }

    /** Goods damaged or lost on the shelf from {@code from} up to {@code to}, newest first. */
    public List<ShelfDamage> damages(Instant from, Instant to) {
        return jdbc.sql("""
                        select id, created_at, variant_id, -on_hand_delta, reason, note, user_id
                        from stock_movements
                        where business_id = :business and type = 'DAMAGE' and created_at >= :from and created_at < :to
                        order by created_at desc, id desc
                        """)
                .param("business", TenantScope.current().orElseThrow())
                .param("from", Timestamp.from(from))
                .param("to", Timestamp.from(to))
                .query((rs, n) -> new ShelfDamage(rs.getObject(1, UUID.class), rs.getTimestamp(2).toInstant(),
                        rs.getObject(3, UUID.class), rs.getInt(4),
                        rs.getString(5) == null ? null : DamageReason.valueOf(rs.getString(5)), rs.getString(6),
                        rs.getObject(7, UUID.class)))
                .list();
    }

    /** The level of each variant; a variant that never had stock is 0 / 0. */
    public Map<UUID, StockLevel> levels(Collection<UUID> variantIds) {
        Map<UUID, StockLevel> levels = new LinkedHashMap<>();
        variantIds.forEach(id -> levels.put(id, new StockLevel(id, 0, 0)));
        if (variantIds.isEmpty()) {
            return levels;
        }
        jdbc.sql("select variant_id, on_hand, reserved from stock_levels where business_id = :business and variant_id in (:ids)")
                .param("business", TenantScope.current().orElseThrow())
                .param("ids", variantIds)
                .query((row, n) -> new StockLevel(row.getObject(1, UUID.class), row.getInt(2), row.getInt(3)))
                .list()
                .forEach(level -> levels.put(level.variantId(), level));
        return levels;
    }
}
