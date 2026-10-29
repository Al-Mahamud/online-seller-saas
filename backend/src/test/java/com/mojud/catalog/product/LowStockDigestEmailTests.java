package com.mojud.catalog.product;

import static org.assertj.core.api.Assertions.assertThat;

import com.mojud.shared.email.EmailComposer;
import com.mojud.shared.email.EmailMessage;
import com.mojud.support.IntegrationTest;
import com.mojud.support.Snapshots;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** The low-stock digest in both languages (implementation-plan.md 13a), against reviewed snapshots. */
@IntegrationTest
class LowStockDigestEmailTests {

    static final String WEB = "https://app.mojud.example";

    @Autowired
    EmailComposer composer;

    @Test
    void theDigestMatchesItsSnapshots() {
        List<LowStock.Item> items = List.of(
                item("Jamdani Saree", "", "JAM-01", 0, 3),
                item("Cotton Kurti", "M / Black", "KUR-M-BLK", 2, 5));
        Snapshots.matchesEmail("low-stock-en", LowStockDigest.email(composer.in(Locale.ENGLISH), items, "Rina's Boutique", WEB));
        Snapshots.matchesEmail("low-stock-bn", LowStockDigest.email(composer.in(Locale.forLanguageTag("bn")),
                List.of(item("জামদানি শাড়ি", "", "JAM-01", 0, 3), item("সুতি কুর্তি", "M / কালো", "KUR-M-BLK", 2, 5)),
                "রিনার বুটিক", WEB));
    }

    @Test
    void aLongListIsCutWithACount() {
        List<LowStock.Item> items = new ArrayList<>();
        for (int i = 0; i < LowStockDigest.ROWS + 4; i++) {
            items.add(item("Product " + i, "", "SKU-" + i, 0, 1));
        }
        EmailMessage email = LowStockDigest.email(composer.in(Locale.ENGLISH), items, "Shop", WEB);
        assertThat(email.subject()).isEqualTo("Shop: 34 products are running low");
        assertThat(email.text()).contains("Product 29").doesNotContain("Product 30").contains("And 4 more.");
    }

    private static LowStock.Item item(String name, String label, String sku, int available, int threshold) {
        return new LowStock.Item(UUID.randomUUID(), name, UUID.randomUUID(), label, sku, available, threshold);
    }
}
