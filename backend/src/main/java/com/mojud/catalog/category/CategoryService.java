package com.mojud.catalog.category;

import com.mojud.catalog.CatalogLimits;
import com.mojud.shared.db.AdvisoryLocks;
import com.mojud.shared.db.Constraints;
import com.mojud.shared.error.ApiException;
import com.mojud.shared.error.ErrorCode;
import com.mojud.shared.text.Text;
import com.mojud.tenancy.CurrentBusiness;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Categories of the selected business (implementation-plan.md 3a): two levels, names unique among
 * siblings (the unique index decides), ordered by position within each group.
 *
 * <p>Every change to the tree's shape (create, move, reorder, delete) takes the business's category
 * lock first, so the depth checks and positions always see the previous change: two moves at once
 * cannot build a loop or a third level. Category edits are rare, so the waiting is never felt.
 */
@Service
class CategoryService {

    static final int MAX_NAME = CatalogLimits.CATEGORY_NAME;

    private static final Map<String, ErrorCode> CONSTRAINT_ERRORS = Map.of(
            "uq_categories_sibling_name", ErrorCode.CATEGORY_NAME_TAKEN,
            // A subcategory added despite the lock (or by future code that forgets it).
            "fk_categories_parent", ErrorCode.CATEGORY_NOT_EMPTY,
            // Products still in the category.
            "fk_products_category", ErrorCode.CATEGORY_NOT_EMPTY);

    /** {@code productCount}: the products directly in it, archived ones too; a top-level one's subcategories count their own. */
    record CategoryNode(UUID id, String name, int position, int productCount, List<CategoryNode> children) {
    }

    private final CategoryRepository categories;
    private final TransactionTemplate transactions;
    private final AdvisoryLocks locks;
    private final Clock clock;

    CategoryService(CategoryRepository categories, TransactionTemplate transactions, AdvisoryLocks locks, Clock clock) {
        this.categories = categories;
        this.transactions = transactions;
        this.locks = locks;
        this.clock = clock;
    }

    /** The whole tree: top-level categories, each with its subcategories, in order. */
    @Transactional(readOnly = true)
    List<CategoryNode> tree() {
        List<Category> all = categories.findAllInOrder();
        Map<UUID, Integer> counts = new HashMap<>();
        for (Object[] row : categories.productCounts(CurrentBusiness.require().businessId())) {
            counts.put((UUID) row[0], ((Number) row[1]).intValue());
        }
        Map<UUID, List<CategoryNode>> children = new LinkedHashMap<>();
        for (Category category : all) {
            if (!category.isTopLevel()) {
                children.computeIfAbsent(category.getParentId(), parent -> new ArrayList<>())
                        .add(node(category, counts.getOrDefault(category.getId(), 0)));
            }
        }
        return all.stream().filter(Category::isTopLevel)
                .map(top -> new CategoryNode(top.getId(), top.getName(), top.getPosition(),
                        counts.getOrDefault(top.getId(), 0),
                        List.copyOf(children.getOrDefault(top.getId(), List.of()))))
                .toList();
    }

    /** Added at the end of its group. */
    CategoryNode create(String name, UUID parentId) {
        String cleaned = Text.cleanName(name, "name", MAX_NAME);
        return changingTree(() -> {
            if (parentId != null) {
                requireTopLevel(find(parentId));
            }
            Category created = categories.saveAndFlush(
                    new Category(parentId, cleaned, categories.nextPosition(parentId), clock.instant()));
            return node(created, 0);
        });
    }

    /** Renaming leaves the tree's shape alone, so it takes no lock; the unique index decides clashes. */
    CategoryNode rename(UUID id, String name) {
        String cleaned = Text.cleanName(name, "name", MAX_NAME);
        return Constraints.translating(transactions, CONSTRAINT_ERRORS, () -> {
            Category category = find(id);
            category.rename(cleaned, clock.instant());
            return counted(categories.saveAndFlush(category));
        });
    }

    /**
     * Moves a category to the end of another group: under a top-level category, or to the top
     * level when {@code parentId} is null. Moving into the group it is already in changes nothing.
     * A category with subcategories stays top-level.
     */
    CategoryNode move(UUID id, UUID parentId) {
        return changingTree(() -> {
            Category category = find(id);
            if (java.util.Objects.equals(category.getParentId(), parentId)) {
                return counted(category);
            }
            if (parentId != null) {
                if (parentId.equals(id)) {
                    throw new ApiException(ErrorCode.CATEGORY_TOO_DEEP);
                }
                requireTopLevel(find(parentId));
                if (categories.existsByParentId(id)) {
                    throw new ApiException(ErrorCode.CATEGORY_TOO_DEEP);
                }
            }
            category.moveTo(parentId, categories.nextPosition(parentId), clock.instant());
            return counted(categories.saveAndFlush(category));
        });
    }

    /**
     * Puts one group in the given order: the top level, or the subcategories of a top-level
     * category. {@code ids} must list each category of the group once.
     */
    void reorder(UUID parentId, List<UUID> ids) {
        changingTree(() -> {
            if (parentId != null) {
                requireTopLevel(find(parentId));
            }
            List<Category> group = categories.findSiblings(parentId);
            Set<UUID> given = new HashSet<>(ids);
            Set<UUID> actual = new HashSet<>(group.stream().map(Category::getId).toList());
            if (given.size() != ids.size() || !given.equals(actual)) {
                throw ApiException.invalidField("ids", "field.order_ids");
            }
            Instant now = clock.instant();
            Map<UUID, Category> byId = new LinkedHashMap<>();
            group.forEach(category -> byId.put(category.getId(), category));
            for (int position = 0; position < ids.size(); position++) {
                byId.get(ids.get(position)).placeAt(position, now);
            }
            return null;
        });
    }

    /** Only an empty category: no subcategories (checked) and no products (the foreign key refuses). */
    void delete(UUID id) {
        changingTree(() -> {
            Category category = find(id);
            if (categories.existsByParentId(id)) {
                throw new ApiException(ErrorCode.CATEGORY_NOT_EMPTY);
            }
            categories.delete(category);
            categories.flush();
            return null;
        });
    }

    private <T> T changingTree(Supplier<T> work) {
        return Constraints.translating(transactions, CONSTRAINT_ERRORS, () -> {
            locks.lockForTransaction("categories:" + CurrentBusiness.require().businessId());
            return work.get();
        });
    }

    private Category find(UUID id) {
        return categories.findById(id).orElseThrow(ApiException::notFound);
    }

    private static void requireTopLevel(Category parent) {
        if (!parent.isTopLevel()) {
            throw new ApiException(ErrorCode.CATEGORY_TOO_DEEP);
        }
    }

    private CategoryNode counted(Category category) {
        return node(category, categories.countProducts(CurrentBusiness.require().businessId(), category.getId()));
    }

    private static CategoryNode node(Category category, int productCount) {
        return new CategoryNode(category.getId(), category.getName(), category.getPosition(), productCount, List.of());
    }
}
