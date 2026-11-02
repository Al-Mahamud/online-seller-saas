package com.mojud.reporting;

import com.mojud.catalog.CatalogLookup;
import com.mojud.catalog.CatalogReports;
import com.mojud.identity.UserDirectory;
import com.mojud.inventory.DamageReason;
import com.mojud.inventory.ShelfDamage;
import com.mojud.inventory.StockQueries;
import com.mojud.orders.OrderReports;
import com.mojud.shared.paging.PageQuery;
import com.mojud.shared.paging.PagedResult;
import com.mojud.shared.text.Text;
import com.mojud.tenancy.BusinessRole;
import com.mojud.tenancy.CurrentBusiness;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Goods damaged or lost on the shelf (stock adjustments) and goods that came back damaged from
 * customers (returns written off), over a range of Dhaka days. Loss is at cost: the product's
 * cost price now for the shelf, the order's for returns. Money only for managers and owners.
 */
@Service
class DamageReports {

    static final int TOP_PRODUCTS = 5;

    enum Source {
        SHELF, RETURN
    }

    /**
     * One damage: on the shelf, {@code reason} as picked and the note typed; from a return, the
     * return's reason as {@code note}, with its order. {@code unitCost} and {@code loss} are null
     * for staff, or when there's no cost price.
     */
    record Damage(String id, Instant at, Source source, UUID variantId, UUID productId, String productName,
            String variantLabel, int quantity, @Nullable DamageReason reason, @Nullable String note,
            @Nullable UUID orderId, @Nullable String orderNumber, @Nullable String recordedBy,
            @Nullable Long unitCost, @Nullable Long loss) {
    }

    record TopProduct(UUID productId, String name, int quantity, long loss) {
    }

    /**
     * {@code quantityBefore}: as many days just before, null when the plan doesn't reach them.
     * {@code loss}, {@code withoutCost} (variants with no cost price) and {@code top} (most loss
     * first) are null for staff.
     */
    record Summary(LocalDate from, LocalDate to, int quantity, int shelfQuantity, int returnQuantity,
            @Nullable Integer quantityBefore, @Nullable Long loss, @Nullable Integer withoutCost,
            @Nullable List<TopProduct> top) {
    }

    private final StockQueries stock;
    private final OrderReports orders;
    private final CatalogLookup catalog;
    private final CatalogReports catalogReports;
    private final UserDirectory users;
    private final ReportRange ranges;

    DamageReports(StockQueries stock, OrderReports orders, CatalogLookup catalog, CatalogReports catalogReports,
            UserDirectory users, ReportRange ranges) {
        this.stock = stock;
        this.orders = orders;
        this.catalog = catalog;
        this.catalogReports = catalogReports;
        this.users = users;
        this.ranges = ranges;
    }

    /** Newest first; {@code source} null for both; {@code q} matches product names and SKUs. */
    @Transactional(readOnly = true)
    PagedResult<Damage> list(LocalDate from, LocalDate to, @Nullable Source source, @Nullable String q, PageQuery paging) {
        List<Damage> all = load(ranges.resolve(from, to), matching(q)).stream()
                .filter(damage -> source == null || damage.source() == source)
                .toList();
        return PagedResult.ofAll(all, paging);
    }

    @Transactional(readOnly = true)
    Summary summary(LocalDate from, LocalDate to, @Nullable String q) {
        ReportRange.Range range = ranges.resolve(from, to);
        Set<UUID> variants = matching(q);
        List<Damage> all = load(range, variants);
        ReportRange.Range before = range.before();
        Integer quantityBefore = ranges.reaches(before.from())
                ? quantity(load(before, variants).stream()) : null;
        int shelf = quantity(all.stream().filter(damage -> damage.source() == Source.SHELF));
        int returned = quantity(all.stream().filter(damage -> damage.source() == Source.RETURN));
        if (!seesMoney()) {
            return new Summary(range.from(), range.to(), shelf + returned, shelf, returned, quantityBefore, null, null, null);
        }
        long loss = all.stream().mapToLong(damage -> damage.loss() == null ? 0 : damage.loss()).sum();
        int withoutCost = (int) all.stream().filter(damage -> damage.unitCost() == null)
                .map(Damage::variantId).distinct().count();
        Map<UUID, TopProduct> byProduct = new LinkedHashMap<>();
        for (Damage damage : all) {
            byProduct.merge(damage.productId(),
                    new TopProduct(damage.productId(), damage.productName(), damage.quantity(),
                            damage.loss() == null ? 0 : damage.loss()),
                    (a, b) -> new TopProduct(a.productId(), a.name(), a.quantity() + b.quantity(), a.loss() + b.loss()));
        }
        List<TopProduct> top = byProduct.values().stream()
                .filter(product -> product.loss() > 0)
                .sorted(Comparator.comparingLong(TopProduct::loss).reversed().thenComparing(TopProduct::name))
                .limit(TOP_PRODUCTS)
                .toList();
        return new Summary(range.from(), range.to(), shelf + returned, shelf, returned, quantityBefore, loss,
                withoutCost, top);
    }

    /** The variants {@code q} names, or null for no search. */
    private @Nullable Set<UUID> matching(@Nullable String q) {
        if (q == null || q.isBlank()) {
            return null;
        }
        return catalogReports.variantsMatching(Text.cleanName(q, "q", 100));
    }

    /** Both kinds over the range, newest first; only {@code variants} when given. */
    private List<Damage> load(ReportRange.Range range, @Nullable Set<UUID> variants) {
        List<ShelfDamage> shelf = stock.damages(range.start(), range.end()).stream()
                .filter(damage -> variants == null || variants.contains(damage.variantId()))
                .toList();
        List<OrderReports.ReturnWriteOff> returned = orders.writeOffs(range.start(), range.end()).stream()
                .filter(damage -> variants == null || variants.contains(damage.variantId()))
                .toList();
        Set<UUID> variantIds = new HashSet<>();
        Set<UUID> userIds = new HashSet<>();
        shelf.forEach(damage -> {
            variantIds.add(damage.variantId());
            if (damage.userId() != null) {
                userIds.add(damage.userId());
            }
        });
        returned.forEach(damage -> {
            variantIds.add(damage.variantId());
            if (damage.userId() != null) {
                userIds.add(damage.userId());
            }
        });
        Map<UUID, CatalogLookup.VariantInfo> info = catalog.variants(variantIds);
        Map<UUID, String> names = new HashMap<>();
        users.summaries(userIds).forEach((id, user) -> names.put(id, user.name()));
        boolean money = seesMoney();

        List<Damage> all = new ArrayList<>();
        for (ShelfDamage damage : shelf) {
            CatalogLookup.VariantInfo variant = info.get(damage.variantId());
            Long cost = variant == null ? null : variant.costPrice();
            all.add(damage(damage.id().toString(), damage.at(), Source.SHELF, damage.variantId(), variant,
                    damage.quantity(), damage.reason(), damage.note(), null, null, names.get(damage.userId()),
                    money ? cost : null));
        }
        for (OrderReports.ReturnWriteOff damage : returned) {
            all.add(damage(damage.returnId() + ":" + damage.variantId(), damage.at(), Source.RETURN,
                    damage.variantId(), info.get(damage.variantId()), damage.quantity(), null, damage.reason(),
                    damage.orderId(), damage.orderNumber(), names.get(damage.userId()),
                    money ? damage.unitCost() : null));
        }
        all.sort(Comparator.comparing(Damage::at).reversed().thenComparing(Damage::id));
        return all;
    }

    private static Damage damage(String id, Instant at, Source source, UUID variantId,
            CatalogLookup.@Nullable VariantInfo variant, int quantity, @Nullable DamageReason reason,
            @Nullable String note, @Nullable UUID orderId, @Nullable String orderNumber,
            @Nullable String recordedBy, @Nullable Long unitCost) {
        return new Damage(id, at, source, variantId, variant == null ? variantId : variant.productId(),
                variant == null ? "" : variant.productName(), variant == null ? "" : variant.label(), quantity,
                reason, note, orderId, orderNumber, recordedBy, unitCost,
                unitCost == null ? null : unitCost * quantity);
    }

    private static int quantity(Stream<Damage> damages) {
        return damages.mapToInt(Damage::quantity).sum();
    }

    private static boolean seesMoney() {
        return CurrentBusiness.require().isAtLeast(BusinessRole.MANAGER);
    }
}
