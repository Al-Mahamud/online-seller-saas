package com.mojud.catalog.product;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SkuGeneratorTest {

    @Test
    void skusAreBuiltFromTheNameAndValues() {
        List<Map<String, String>> grid = VariantGrid.combinations(List.of(
                new VariantGrid.Option("Size", List.of("S", "XL")),
                new VariantGrid.Option("Color", List.of("Black", "Navy Blue"))));

        List<String> skus = SkuGenerator.generate(SkuGenerator.base("Cotton Kurti"), grid,
                Map.of("Size", List.of("S", "XL"), "Color", List.of("Black", "Navy Blue")), Set.of());

        assertThat(skus).containsExactly("COTTONKU-S-BLA", "COTTONKU-S-NAV", "COTTONKU-XL-BLA", "COTTONKU-XL-NAV");
    }

    @Test
    void banglaNamesAndValuesFallBackToCodes() {
        String base = SkuGenerator.base("লাল শাড়ি");
        List<String> skus = SkuGenerator.generate(base, List.of(Map.of("রং", "লাল"), Map.of("রং", "নীল")),
                Map.of("রং", List.of("লাল", "নীল")), Set.of());

        assertThat(base).matches("P[A-Z2-9]{5}");
        assertThat(skus).containsExactly(base + "-V1", base + "-V2");
    }

    @Test
    void takenSkusGetASuffixIgnoringCase() {
        List<String> skus = SkuGenerator.generate("KURTI", List.of(Map.of(), Map.of()), Map.of(),
                Set.of("KURTI", "KURTI-2"));

        assertThat(skus).containsExactly("KURTI-3", "KURTI-4");
    }
}
