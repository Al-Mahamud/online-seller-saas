package com.mojud.catalog.category;

import com.mojud.catalog.CatalogImport;
import com.mojud.catalog.CatalogLimits;
import com.mojud.shared.db.AdvisoryLocks;
import com.mojud.shared.tenant.TenantScope;
import com.mojud.shared.text.Text;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Categories by name for imports: "Name" or "Parent > Name", made when missing. Takes the same lock
 * as CategoryService's tree changes, so it sees (and makes) the same tree. Within the catalog only.
 */
@Component
public class CategoryPaths {

    private final CategoryRepository categories;
    private final AdvisoryLocks locks;
    private final Clock clock;

    CategoryPaths(CategoryRepository categories, AdvisoryLocks locks, Clock clock) {
        this.categories = categories;
        this.locks = locks;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public UUID resolve(String text) {
        String[] parts = text.split(">", -1);
        if (parts.length > 2) {
            throw new CatalogImport.Rejected("category_too_deep");
        }
        String name = Text.cleanName(parts[parts.length - 1], "category", CatalogLimits.CATEGORY_NAME);
        String parentName = parts.length == 2 ? Text.cleanName(parts[0], "category", CatalogLimits.CATEGORY_NAME) : null;
        locks.lockForTransaction("categories:" + TenantScope.current().orElseThrow());
        List<Category> all = categories.findAll();
        if (parentName == null) {
            List<Category> named = all.stream().filter(c -> same(c.getName(), name)).toList();
            if (named.size() > 1) {
                throw new CatalogImport.Rejected("category_ambiguous");
            }
            return named.isEmpty() ? create(null, name) : named.get(0).getId();
        }
        Optional<Category> parent = all.stream().filter(c -> c.isTopLevel() && same(c.getName(), parentName)).findFirst();
        if (parent.isEmpty()) {
            if (all.stream().anyMatch(c -> same(c.getName(), parentName))) {
                throw new CatalogImport.Rejected("category_too_deep");
            }
            return create(create(null, parentName), name);
        }
        UUID parentId = parent.get().getId();
        return all.stream().filter(c -> parentId.equals(c.getParentId()) && same(c.getName(), name))
                .map(Category::getId).findFirst()
                .orElseGet(() -> create(parentId, name));
    }

    private UUID create(UUID parentId, String name) {
        return categories.saveAndFlush(new Category(parentId, name, categories.nextPosition(parentId), clock.instant()))
                .getId();
    }

    private static boolean same(String a, String b) {
        return a.toLowerCase(Locale.ROOT).equals(b.toLowerCase(Locale.ROOT));
    }
}
