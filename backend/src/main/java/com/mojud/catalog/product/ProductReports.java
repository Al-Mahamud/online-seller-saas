package com.mojud.catalog.product;

import com.mojud.catalog.CatalogReports;
import com.mojud.shared.tenant.TenantScope;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** {@link CatalogReports}: the catalog's report figures. */
@Component
class ProductReports implements CatalogReports {

    private final LowStock lowStock;
    private final JdbcClient jdbc;

    ProductReports(LowStock lowStock, DataSource dataSource) {
        this.lowStock = lowStock;
        this.jdbc = JdbcClient.create(dataSource);
    }

    @Override
    @Transactional(readOnly = true)
    public int lowStockCount() {
        return lowStock.list().size();
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, String> categoryPaths(Collection<UUID> variantIds) {
        Map<UUID, String> paths = new HashMap<>();
        if (variantIds.isEmpty()) {
            return paths;
        }
        jdbc.sql("""
                        select v.id, case when parent.id is null then c.name else parent.name || ' > ' || c.name end
                        from product_variants v
                        join products p on p.id = v.product_id
                        join categories c on c.id = p.category_id
                        left join categories parent on parent.id = c.parent_id
                        where v.business_id = ? and v.id = any (?)
                        """)
                .params(TenantScope.current().orElseThrow(), variantIds.toArray(UUID[]::new))
                .query((rs, n) -> paths.put(rs.getObject(1, UUID.class), rs.getString(2)))
                .list();
        return paths;
    }
}
