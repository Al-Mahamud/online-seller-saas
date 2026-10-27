package com.mojud.catalog;

import com.mojud.shared.tenant.TenantScope;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
class JdbcCatalogLookup implements CatalogLookup {

    private final JdbcClient jdbc;

    JdbcCatalogLookup(DataSource dataSource) {
        this.jdbc = JdbcClient.create(dataSource);
    }

    @Override
    @Transactional(readOnly = true)
    public List<CategoryRef> categories() {
        return jdbc.sql("""
                        select c.id, c.name, p.id, p.name from categories c
                        left join categories p on p.business_id = c.business_id and p.id = c.parent_id
                        where c.business_id = ?
                        order by c.name, c.id
                        """)
                .param(TenantScope.current().orElseThrow())
                .query((row, n) -> new CategoryRef(row.getObject(1, UUID.class), row.getString(2),
                        row.getObject(3, UUID.class), row.getString(4)))
                .list();
    }

    @Override
    @Transactional(readOnly = true)
    public Set<String> takenSkus(Collection<String> skus) {
        if (skus.isEmpty()) {
            return Set.of();
        }
        String[] upper = skus.stream().map(sku -> sku.toUpperCase(Locale.ROOT)).distinct().toArray(String[]::new);
        return new HashSet<>(jdbc.sql("select upper(sku) from product_variants where business_id = ? and upper(sku) = any (?)")
                .params(TenantScope.current().orElseThrow(), upper)
                .query(String.class)
                .list());
    }

    @Override
    @Transactional(readOnly = true)
    public Set<String> activeProductNames(Collection<String> names) {
        if (names.isEmpty()) {
            return Set.of();
        }
        String[] lower = names.stream().map(name -> name.toLowerCase(Locale.ROOT)).distinct().toArray(String[]::new);
        return new HashSet<>(jdbc.sql("select lower(name) from products where business_id = ? and status = 'ACTIVE' and lower(name) = any (?)")
                .params(TenantScope.current().orElseThrow(), lower)
                .query(String.class)
                .list());
    }
}
