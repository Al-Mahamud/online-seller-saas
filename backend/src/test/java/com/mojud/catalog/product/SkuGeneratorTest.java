package com.mojud.catalog.product;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SkuGeneratorTest {

    private static final UUID PRODUCT = UUID.fromString("01a1b5b8-3d32-7586-aa42-c5f70952a6ff");

    @Test
    void skusAreBuiltFromTheNameAndValues() {
        List<Map<String, String>> grid = VariantGrid.combinations(List.of(
                new VariantGrid.Option("Size", List.of("S", "XL")),
                new VariantGrid.Option("Color", List.of("Black", "Navy Blue"))));

        List<String> skus = SkuGenerator.generate(SkuGenerator.base("Cotton Kurti", PRODUCT, List.of()), grid,
                Map.of("Size", List.of("S", "XL"), "Color", List.of("Black", "Navy Blue")), Set.of());

        assertThat(skus).containsExactly("COTTONKU-S-BLA", "COTTONKU-S-NAV", "COTTONKU-XL-BLA", "COTTONKU-XL-NAV");
    }

    @Test
    void banglaNamesAndValuesFallBackToCodes() {
        String base = SkuGenerator.base("লাল শাড়ি", PRODUCT, List.of());
        List<String> skus = SkuGenerator.generate(base, List.of(Map.of("রং", "লাল"), Map.of("রং", "নীল")),
                Map.of("রং", List.of("লাল", "নীল")), Set.of());

        assertThat(base).matches("P[A-Z2-9]{5}");
        assertThat(skus).containsExactly(base + "-V1", base + "-V2");
    }

    /** A variant added later starts like the ones before it, even ones made before codes were kept. */
    @Test
    void aBanglaNamedProductKeepsItsCode() {
        assertThat(SkuGenerator.base("লাল শাড়ি", PRODUCT, List.of())).isEqualTo(SkuGenerator.base("লাল শাড়ি", PRODUCT, List.of()));
        assertThat(SkuGenerator.base("লাল শাড়ি", PRODUCT, List.of("MY-OWN-SKU", "P4PQZU-V1-M"))).isEqualTo("P4PQZU");
        assertThat(SkuGenerator.base("লাল শাড়ি", PRODUCT, List.of("P4PQZU"))).isEqualTo("P4PQZU");
        assertThat(SkuGenerator.base("লাল শাড়ি", UUID.randomUUID(), List.of())).isNotEqualTo(SkuGenerator.base("লাল শাড়ি", PRODUCT, List.of()));
        // A name with Latin letters keeps using them, whatever the variants are called.
        assertThat(SkuGenerator.base("Silk Saree", PRODUCT, List.of("P4PQZU-V1"))).isEqualTo("SILKSARE");
    }

    @Test
    void takenSkusGetASuffixIgnoringCase() {
        List<String> skus = SkuGenerator.generate("KURTI", List.of(Map.of(), Map.of()), Map.of(),
                Set.of("KURTI", "KURTI-2"));

        assertThat(skus).containsExactly("KURTI-3", "KURTI-4");
    }
}
