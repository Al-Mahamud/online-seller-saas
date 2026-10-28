package com.mojud.catalog.product;

import com.mojud.inventory.StockLevel;
import com.mojud.inventory.StockQueries;
import com.mojud.shared.csv.CsvWriter;
import com.mojud.shared.tenant.TenantScope;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.context.MessageSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Products with stock as CSV (implementation-plan.md 9b), one row per variant. The first columns
 * are the import template's, in the reader's language, so the file can be edited and imported
 * again (an unchanged export imports as "nothing changed"); reserved, available and status follow.
 */
@Component
class ProductExport {

    static final int BATCH = 500;

    private record VariantRow(UUID id, String product, String sku, long price, Long cost, String category,
            Map<String, String> options, String description, Integer threshold, String status) {
    }

    private final JdbcTemplate jdbc;
    private final StockQueries stock;
    private final MessageSource messages;
    private final JsonMapper json;

    ProductExport(DataSource dataSource, StockQueries stock, MessageSource messages, JsonMapper json) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.jdbc.setFetchSize(BATCH);
        this.stock = stock;
        this.messages = messages;
        this.json = json;
    }

    @Transactional(readOnly = true)
    void write(OutputStream out, Locale locale) {
        UUID business = TenantScope.current().orElseThrow();
        // Every option name in use, once (ignoring case), each a column like an import's "Size".
        List<String> optionNames = jdbc.queryForList("""
                        select min(name) from product_options where business_id = ?
                        group by lower(name) order by min(position), lower(name)
                        """, String.class, business);
        try (CsvWriter csv = new CsvWriter(out)) {
            List<String> header = new ArrayList<>();
            for (String field : List.of("NAME", "SKU", "PRICE", "COST_PRICE", "STOCK", "CATEGORY")) {
                header.add(messages.getMessage("import.template." + field, null, field, locale));
            }
            header.addAll(optionNames);
            header.add(messages.getMessage("import.template.DESCRIPTION", null, "Description", locale));
            header.add(messages.getMessage("import.template.LOW_STOCK_THRESHOLD", null, "Low stock alert", locale));
            for (String extra : List.of("reserved", "available", "status")) {
                header.add(messages.getMessage("export.products." + extra, null, extra, locale));
            }
            csv.row(header);
            List<VariantRow> batch = new ArrayList<>(BATCH);
            jdbc.query("""
                            select v.id, p.name, v.sku, v.price, v.cost_price,
                                   case when c.id is null then null when parent.id is null then c.name
                                        else parent.name || ' > ' || c.name end,
                                   v.attributes::text, p.description, v.low_stock_threshold,
                                   case when p.status = 'ACTIVE' and v.status = 'ACTIVE' then 'ACTIVE' else 'ARCHIVED' end
                            from product_variants v
                            join products p on p.id = v.product_id
                            left join categories c on c.id = p.category_id
                            left join categories parent on parent.id = c.parent_id
                            where v.business_id = ?
                            order by p.created_at, p.id, v.position
                            """,
                    rs -> {
                        batch.add(new VariantRow(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getLong(4),
                                (Long) rs.getObject(5), rs.getString(6), attributes(rs.getString(7)), rs.getString(8),
                                (Integer) rs.getObject(9), rs.getString(10)));
                        if (batch.size() == BATCH) {
                            flush(csv, batch, optionNames);
                        }
                    },
                    business);
            flush(csv, batch, optionNames);
        }
    }

    private void flush(CsvWriter csv, List<VariantRow> batch, List<String> optionNames) {
        if (batch.isEmpty()) {
            return;
        }
        Map<UUID, StockLevel> levels = stock.levels(batch.stream().map(VariantRow::id).toList());
        for (VariantRow row : batch) {
            StockLevel level = levels.get(row.id());
            csv.text(row.product()).text(row.sku()).taka(row.price()).optionalTaka(row.cost()).number(level.onHand())
                    .text(row.category());
            for (String option : optionNames) {
                csv.text(row.options().entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase(option))
                        .map(Map.Entry::getValue).findFirst().orElse(""));
            }
            csv.text(row.description())
                    .text(row.threshold() == null ? "" : row.threshold().toString())
                    .number(level.reserved()).number(level.available()).text(row.status())
                    .end();
        }
        batch.clear();
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> attributes(String text) {
        return text == null ? Map.of() : json.readValue(text, LinkedHashMap.class);
    }
}
