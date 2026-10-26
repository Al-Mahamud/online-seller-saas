package com.mojud.inventory;

import com.mojud.shared.paging.Cursor;
import com.mojud.shared.paging.CursorPage;
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

    private static final Instant END_OF_TIME = Instant.parse("9999-12-31T00:00:00Z");
    private static final UUID LAST_ID = new UUID(-1L, -1L);

    private final JdbcClient jdbc;

    StockQueries(DataSource dataSource) {
        this.jdbc = JdbcClient.create(dataSource);
    }

    /**
     * A variant's stock history, newest first. The caller has checked the variant exists in this
     * business; for any other id the history is simply empty.
     */
    public CursorPage<StockHistory> history(UUID variantId, String cursor, Integer limit) {
        int pageSize = CursorPage.limit(limit);
        Cursor after = Cursor.decode(cursor);
        List<StockHistory> rows = jdbc.sql("""
                        select id, created_at, type, on_hand_delta, reserved_delta, on_hand_after, reserved_after,
                               reference_type, reference_id, note, user_id
                        from stock_movements
                        where business_id = :business and variant_id = :variant
                          and (created_at, id) < (:afterAt, :afterId)
                        order by created_at desc, id desc
                        limit :fetch
                        """)
                .param("business", TenantScope.current().orElseThrow())
                .param("variant", variantId)
                // The first page starts after everything: no null parameter, which PostgreSQL cannot type.
                .param("afterAt", Timestamp.from(after == null ? END_OF_TIME : after.sortKeyAsInstant()))
                .param("afterId", after == null ? LAST_ID : after.id())
                .param("fetch", pageSize + 1)
                .query((rs, n) -> new StockHistory(rs.getObject(1, UUID.class), rs.getTimestamp(2).toInstant(),
                        MovementType.valueOf(rs.getString(3)), rs.getInt(4), rs.getInt(5), rs.getInt(6), rs.getInt(7),
                        rs.getString(8), rs.getObject(9, UUID.class), rs.getString(10), rs.getObject(11, UUID.class)))
                .list();
        return CursorPage.of(rows, pageSize, row -> new Cursor(row.at().toString(), row.id()));
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
