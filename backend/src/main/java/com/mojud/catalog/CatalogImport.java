package com.mojud.catalog;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Writing imported products (implementation-plan.md 5b). Works without a signed-in member (the
 * import runs in the background), in the business of the TenantScope and the caller's transaction.
 */
public interface CatalogImport {

    /** {@code options}: option name to value; null {@code sku} generates one for a new variant. */
    record VariantRow(String sku, Map<String, String> options, long price, Long costPrice, Integer lowStockThreshold) {
    }

    /**
     * One product's rows. {@code category} is "Name" or "Parent > Name" (made when missing); null
     * fields leave an existing product's value alone.
     */
    record ProductRows(String name, String description, String category, List<String> optionNames, List<VariantRow> variants) {
    }

    enum Outcome {
        CREATED, UPDATED, UNCHANGED
    }

    /** For each variant given, in order. */
    record Result(UUID variantId, Outcome outcome) {
    }

    /**
     * The product is the one owning any of the SKUs given, else the one product on sale with this
     * name, else a new one. Only what differs is changed, so applying the same rows twice changes
     * nothing the second time. Variants not found are added (with their option values).
     *
     * @throws Rejected when the rows cannot be applied as they are
     */
    List<Result> apply(ProductRows product);

    /** Refused rows; {@code code} has a message {@code import.error.<code>}. */
    final class Rejected extends RuntimeException {

        private final String code;
        private final transient List<Object> args;

        public Rejected(String code, Object... args) {
            super(code);
            this.code = code;
            this.args = List.of(args);
        }

        public String code() {
            return code;
        }

        public List<Object> args() {
            return args;
        }
    }
}
