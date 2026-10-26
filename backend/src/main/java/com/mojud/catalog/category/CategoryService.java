package com.mojud.catalog.category;

import com.mojud.shared.db.Constraints;
import com.mojud.shared.error.ApiException;
import com.mojud.shared.error.ErrorCode;
import com.mojud.shared.text.Text;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Categories of the selected business (implementation-plan.md 3a): two levels, names unique among
 * siblings (the unique index decides), ordered by position within each group.
 */
@Service
class CategoryService {

    static final int MAX_NAME = 80;

    record CategoryNode(UUID id, String name, int position, List<CategoryNode> children) {
    }

    private final CategoryRepository categories;
    private final TransactionTemplate transactions;
    private final Clock clock;

    CategoryService(CategoryRepository categories, TransactionTemplate transactions, Clock clock) {
        this.categories = categories;
        this.transactions = transactions;
        this.clock = clock;
    }

    /** The whole tree: top-level categories, each with its subcategories, in order. */
    @Transactional(readOnly = true)
    List<CategoryNode> tree() {
        List<Category> all = categories.findAllInOrder();
        Map<UUID, List<CategoryNode>> children = new LinkedHashMap<>();
        for (Category category : all) {
            if (!category.isTopLevel()) {
                children.computeIfAbsent(category.getParentId(), parent -> new ArrayList<>())
                        .add(new CategoryNode(category.getId(), category.getName(), category.getPosition(), List.of()));
            }
        }
        return all.stream().filter(Category::isTopLevel)
                .map(top -> new CategoryNode(top.getId(), top.getName(), top.getPosition(),
                        List.copyOf(children.getOrDefault(top.getId(), List.of()))))
                .toList();
    }

    /** Added at the end of its group. */
    CategoryNode create(String name, UUID parentId) {
        String cleaned = Text.cleanName(name, "name", MAX_NAME);
        return saving(() -> {
            if (parentId != null) {
                requireTopLevel(find(parentId));
            }
            Category category = categories.saveAndFlush(
                    new Category(parentId, cleaned, nextPosition(parentId), clock.instant()));
            return node(category);
        });
    }

    CategoryNode rename(UUID id, String name) {
        String cleaned = Text.cleanName(name, "name", MAX_NAME);
        return saving(() -> {
            Category category = find(id);
            category.rename(cleaned, clock.instant());
            return node(categories.saveAndFlush(category));
        });
    }

    /**
     * Moves a category to another group, at its end: under a top-level category, or to the top
     * level when {@code parentId} is null. A category with subcategories stays top-level.
     */
    CategoryNode move(UUID id, UUID parentId) {
        return saving(() -> {
            Category category = find(id);
            if (parentId != null) {
                if (parentId.equals(id)) {
                    throw new ApiException(ErrorCode.CATEGORY_TOO_DEEP);
                }
                requireTopLevel(find(parentId));
                if (categories.existsByParentId(id)) {
                    throw new ApiException(ErrorCode.CATEGORY_TOO_DEEP);
                }
            }
            category.moveTo(parentId, nextPosition(parentId), clock.instant());
            return node(categories.saveAndFlush(category));
        });
    }

    /** Puts one group in the given order; {@code ids} must list each of its categories once. */
    @Transactional
    void reorder(UUID parentId, List<UUID> ids) {
        if (parentId != null) {
            find(parentId);
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
    }

    /** Only an empty category; Phase 3b adds "no products in it". */
    @Transactional
    void delete(UUID id) {
        Category category = find(id);
        if (categories.existsByParentId(id)) {
            throw new ApiException(ErrorCode.CATEGORY_NOT_EMPTY);
        }
        categories.delete(category);
    }

    private Category find(UUID id) {
        return categories.findById(id).orElseThrow(ApiException::notFound);
    }

    private static void requireTopLevel(Category parent) {
        if (!parent.isTopLevel()) {
            throw new ApiException(ErrorCode.CATEGORY_TOO_DEEP);
        }
    }

    private int nextPosition(UUID parentId) {
        return categories.findSiblings(parentId).stream().mapToInt(Category::getPosition).max().orElse(-1) + 1;
    }

    /** Runs a write in its own transaction and turns a sibling-name clash into category.name_taken. */
    private CategoryNode saving(Supplier<CategoryNode> work) {
        try {
            return transactions.execute(status -> work.get());
        } catch (DataIntegrityViolationException e) {
            if (Constraints.violated(e, "uq_categories_sibling_name")) {
                throw new ApiException(ErrorCode.CATEGORY_NAME_TAKEN);
            }
            throw e;
        }
    }

    private static CategoryNode node(Category category) {
        return new CategoryNode(category.getId(), category.getName(), category.getPosition(), List.of());
    }
}
