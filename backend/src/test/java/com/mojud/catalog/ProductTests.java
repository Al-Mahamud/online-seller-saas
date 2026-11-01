package com.mojud.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import com.mojud.support.ApiClient;
import com.mojud.support.ApiResponse;
import com.mojud.support.CapturedMessages;
import com.mojud.support.IntegrationTest;
import com.mojud.support.TestAccounts;
import com.mojud.support.TestBusinesses;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

/** Products, options and variants (implementation-plan.md 3b). */
@IntegrationTest
class ProductTests {

    @LocalServerPort
    int port;

    @Autowired
    CapturedMessages messages;

    ApiClient owner;

    @BeforeEach
    void business() {
        owner = new ApiClient(port);
        TestAccounts.signUp(owner, messages);
        TestBusinesses.create(owner, "Product Shop");
    }

    // The grid.

    @Test
    void optionsMakeOneVariantPerCombinationWithUniqueSkus() {
        ApiResponse product = createWithOptions("Cotton Kurti",
                option("Size", "S", "M", "L"), option("Color", "Black", "White"));

        assertThat(product.<List<String>>json("$.variants[*].label")).containsExactly(
                "S / Black", "S / White", "M / Black", "M / White", "L / Black", "L / White");
        List<String> skus = product.json("$.variants[*].sku");
        assertThat(new HashSet<>(skus)).hasSize(6);
        assertThat(skus.get(0)).isEqualTo("COTTONKU-S-BLA");
        assertThat(product.<List<Integer>>json("$.variants[*].price")).containsOnly(150000);
        assertThat(product.<List<String>>json("$.options[*].name")).containsExactly("Size", "Color");
    }

    @Test
    void aProductWithoutOptionsHasOnePlainVariant() {
        ApiResponse product = owner.post("/api/v1/products", Map.of("name", "Gift Box", "price", 50000, "sku", "GIFT-1"))
                .expectStatus(201);

        assertThat(product.<List<String>>json("$.variants[*].sku")).containsExactly("GIFT-1");
        assertThat(product.<String>json("$.variants[0].label")).isEmpty();
        assertThat(owner.post("/api/v1/products", Map.of("name", "Gift Box", "price", 50000)).expectStatus(201)
                .<String>json("$.variants[0].sku")).isEqualTo("GIFTBOX");
    }

    @Test
    void skusAreUniqueInABusinessIgnoringCaseButFreeInAnother() {
        owner.post("/api/v1/products", Map.of("name", "One", "price", 1, "sku", "ABC-1")).expectStatus(201);

        owner.post("/api/v1/products", Map.of("name", "Two", "price", 1, "sku", "abc-1"))
                .expectError(409, "product.sku_taken");

        ApiClient other = new ApiClient(port);
        TestAccounts.signUp(other, messages);
        TestBusinesses.create(other, "Other Shop");
        other.post("/api/v1/products", Map.of("name", "Two", "price", 1, "sku", "ABC-1")).expectStatus(201);
    }

    @Test
    void optionsAreValidated() {
        owner.post("/api/v1/products", Map.of("name", "Too many", "price", 1, "options",
                List.of(option("A", "1"), option("B", "1"), option("C", "1"), option("D", "1"))))
                .expectError(400, "validation.failed");
        owner.post("/api/v1/products", Map.of("name", "Duplicates", "price", 1, "options",
                List.of(option("Size", "S", "s")))).expectError(400, "validation.failed");
        owner.post("/api/v1/products", Map.of("name", "Same option", "price", 1, "options",
                List.of(option("Size", "S"), option("SIZE", "M")))).expectError(400, "validation.failed");
        String[] eleven = {"1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11"};
        owner.post("/api/v1/products", Map.of("name", "Huge", "price", 1, "options",
                List.of(option("A", eleven), option("B", eleven)))).expectError(400, "validation.failed");
        owner.post("/api/v1/products", Map.of("name", "Sku with options", "price", 1, "sku", "X",
                "options", List.of(option("A", "1")))).expectError(400, "validation.failed");
    }

    // Changing the grid.

    @Test
    void aRemovedValueArchivesItsVariantsAndComingBackRestoresThem() {
        ApiResponse product = createWithOptions("Saree", option("Size", "S", "M", "L"));
        String id = product.json("$.id");
        String mVariant = product.json("$.variants[1].id");
        owner.patch("/api/v1/variants/" + mVariant, Map.of("price", 99900)).expectStatus(200);

        ApiResponse without = replaceOptions(id, option("Size", "S", "L"));
        assertThat(statusOf(without, "M")).isEqualTo("ARCHIVED");
        assertThat(without.<List<String>>json("$.variants[?(@.status == 'ACTIVE')].label")).containsExactly("S", "L");

        ApiResponse back = replaceOptions(id, option("Size", "S", "M", "L"));
        assertThat(back.<List<String>>json("$.variants[?(@.status == 'ACTIVE')].id")).contains(mVariant);
        assertThat(back.<List<Integer>>json("$.variants[?(@.id == '" + mVariant + "')].price")).containsExactly(99900);
        assertThat(back.<List<String>>json("$.variants[*].label")).containsExactly("S", "M", "L");
    }

    @Test
    void addingAnOptionKeepsTheVariantsAsItsFirstValue() {
        ApiResponse product = createWithOptions("Panjabi", option("Size", "M", "L"));
        String id = product.json("$.id");
        List<String> before = product.json("$.variants[*].id");

        ApiResponse after = replaceOptions(id, option("Size", "M", "L"), option("Color", "Red", "Blue"));

        assertThat(after.<List<String>>json("$.variants[?(@.status == 'ACTIVE')].label"))
                .containsExactly("M / Red", "M / Blue", "L / Red", "L / Blue");
        assertThat(after.<List<String>>json("$.variants[?(@.label == 'M / Red' || @.label == 'L / Red')].id"))
                .containsExactlyElementsOf(before);
    }

    @Test
    void aPlainProductGettingOptionsKeepsItsVariant() {
        ApiResponse product = owner.post("/api/v1/products", Map.of("name", "Scarf", "price", 30000, "sku", "SCARF"))
                .expectStatus(201);
        String plain = product.json("$.variants[0].id");

        ApiResponse after = replaceOptions(product.json("$.id"), option("Color", "Red", "Green"));

        assertThat(after.<List<String>>json("$.variants[?(@.label == 'Red')].id")).containsExactly(plain);
        assertThat(after.<List<String>>json("$.variants[?(@.label == 'Red')].sku")).containsExactly("SCARF");
    }

    @Test
    void renamingAnOptionByIdKeepsItsVariants() {
        ApiResponse product = createWithOptions("Shirt", option("Colour", "Red", "Blue"));
        String optionId = product.json("$.options[0].id");
        List<String> before = product.json("$.variants[*].id");

        ApiResponse after = owner.put("/api/v1/products/" + product.json("$.id") + "/options", Map.of("options",
                List.of(Map.of("id", optionId, "name", "Color", "values", List.of("Red", "Blue"))))).expectStatus(200);

        assertThat(after.<List<String>>json("$.variants[*].id")).containsExactlyElementsOf(before);
        assertThat(after.<String>json("$.variants[0].attributes.Color")).isEqualTo("Red");
        assertThat(after.<String>json("$.options[0].name")).isEqualTo("Color");
    }

    @Test
    void removingAnOptionKeepsOneVariantPerRemainingCombination() {
        ApiResponse product = createWithOptions("Tops", option("Size", "S", "M"), option("Color", "Red", "Blue"));

        ApiResponse after = replaceOptions(product.json("$.id"), option("Size", "S", "M"));

        assertThat(after.<List<String>>json("$.variants[?(@.status == 'ACTIVE')].label")).containsExactly("S", "M");
        assertThat(after.<List<Object>>json("$.variants[?(@.status == 'ARCHIVED')]")).hasSize(2);
    }

    // Prices, costs and roles.

    @Test
    void staffNeverReceiveCostPricesOrMargins() {
        ApiResponse product = owner.post("/api/v1/products",
                Map.of("name", "Costly", "price", 100000, "costPrice", 60000)).expectStatus(201);
        assertThat(product.<Integer>json("$.variants[0].costPrice")).isEqualTo(60000);
        assertThat(product.<Integer>json("$.variants[0].margin")).isEqualTo(40000);

        ApiClient staff = staffMember();
        String detail = staff.get("/api/v1/products/" + product.json("$.id")).expectStatus(200).body();
        String list = staff.get("/api/v1/products").expectStatus(200).body();

        assertThat(detail).doesNotContain("costPrice").doesNotContain("margin").doesNotContain("60000");
        assertThat(list).doesNotContain("costPrice").doesNotContain("60000");
        staff.patch("/api/v1/variants/" + product.json("$.variants[0].id"), Map.of("price", 1))
                .expectError(403, "access.denied");
        staff.post("/api/v1/products", Map.of("name", "x", "price", 1)).expectError(403, "access.denied");
    }

    @Test
    void variantEditsAreAuditedAndStaleOnesRefused() {
        ApiResponse product = owner.post("/api/v1/products", Map.of("name", "Watch", "price", 500000, "costPrice", 300000))
                .expectStatus(201);
        String variant = product.json("$.variants[0].id");
        int version = product.json("$.variants[0].version");

        ApiResponse changed = owner.patch("/api/v1/variants/" + variant,
                Map.of("price", 550000, "clearCostPrice", true, "lowStockThreshold", 3, "version", version)).expectStatus(200);
        assertThat(changed.<Integer>json("$.price")).isEqualTo(550000);
        assertThat(changed.body()).doesNotContain("costPrice");
        assertThat(changed.<Integer>json("$.lowStockThreshold")).isEqualTo(3);

        owner.patch("/api/v1/variants/" + variant, Map.of("price", 1, "version", version))
                .expectError(409, "resource.modified");
        assertThat(owner.patch("/api/v1/variants/" + variant, Map.of("useDefaultThreshold", true, "sku", ""))
                .expectStatus(200).<String>json("$.sku")).isEqualTo("WATCH");

        ApiResponse log = owner.get("/api/v1/business/audit-log").expectStatus(200);
        assertThat(log.<String>json("$.items[0].action")).isEqualTo("variant.prices_changed");
        assertThat(log.<Integer>json("$.items[0].details.changes.price.to")).isEqualTo(550000);
        assertThat(log.<Integer>json("$.items[0].details.changes.costPrice.from")).isEqualTo(300000);
    }

    // Listing, archiving, categories and tags.

    @Test
    void archivedProductsLeaveTheDefaultList() {
        String kept = owner.post("/api/v1/products", Map.of("name", "Kept", "price", 1)).expectStatus(201).json("$.id");
        String gone = owner.post("/api/v1/products", Map.of("name", "Gone", "price", 1)).expectStatus(201).json("$.id");

        owner.post("/api/v1/products/" + gone + "/archive", null).expectStatus(200);

        assertThat(owner.get("/api/v1/products").<List<String>>json("$.items[*].id")).containsExactly(kept);
        assertThat(owner.get("/api/v1/products?status=ARCHIVED").<List<String>>json("$.items[*].id")).containsExactly(gone);
        owner.post("/api/v1/products/" + gone + "/restore", null).expectStatus(200);
        assertThat(owner.get("/api/v1/products").<List<String>>json("$.items[*].id")).containsExactly(gone, kept);
    }

    @Test
    void theListFiltersByCategoryIncludingSubcategoriesAndByTag() {
        String women = owner.post("/api/v1/categories", Map.of("name", "Women")).expectStatus(201).json("$.id");
        String kurti = owner.post("/api/v1/categories", Map.of("name", "Kurti", "parentId", women)).expectStatus(201).json("$.id");
        String eid = owner.post("/api/v1/tags", Map.of("name", "eid")).expectStatus(201).json("$.id");
        String inKurti = owner.post("/api/v1/products", Map.of("name", "In Kurti", "price", 1, "categoryId", kurti,
                "tagIds", List.of(eid))).expectStatus(201).json("$.id");
        String inWomen = owner.post("/api/v1/products", Map.of("name", "In Women", "price", 1, "categoryId", women))
                .expectStatus(201).json("$.id");
        owner.post("/api/v1/products", Map.of("name", "Elsewhere", "price", 1)).expectStatus(201);

        assertThat(owner.get("/api/v1/products?categoryId=" + women).<List<String>>json("$.items[*].id"))
                .containsExactlyInAnyOrder(inKurti, inWomen);
        assertThat(owner.get("/api/v1/products?categoryId=" + kurti).<List<String>>json("$.items[*].id"))
                .containsExactly(inKurti);
        assertThat(owner.get("/api/v1/products?tagId=" + eid).<List<String>>json("$.items[*].id")).containsExactly(inKurti);
    }

    @Test
    void theListIsPagedWithPricesOfActiveVariants() {
        for (int i = 1; i <= 3; i++) {
            owner.post("/api/v1/products", Map.of("name", "Item " + i, "price", i * 100)).expectStatus(201);
        }
        String gridded = createWithOptions("Gridded", option("Size", "S", "M")).json("$.id");
        owner.patch("/api/v1/variants/" + owner.get("/api/v1/products/" + gridded).json("$.variants[1].id"),
                Map.of("price", 200000)).expectStatus(200);

        ApiResponse first = owner.get("/api/v1/products?size=2").expectStatus(200);
        ApiResponse second = owner.get("/api/v1/products?size=2&page=2");

        assertThat(first.<List<String>>json("$.items[*].name")).containsExactly("Gridded", "Item 3");
        assertThat(first.<Integer>json("$.items[0].minPrice")).isEqualTo(150000);
        assertThat(first.<Integer>json("$.items[0].maxPrice")).isEqualTo(200000);
        assertThat(first.<Integer>json("$.items[0].activeVariants")).isEqualTo(2);
        assertThat(second.<List<String>>json("$.items[*].name")).containsExactly("Item 2", "Item 1");
        assertThat(second.<Integer>json("$.page")).isEqualTo(2);
        assertThat(second.<Integer>json("$.totalItems")).isEqualTo(4);
        assertThat(second.<Integer>json("$.totalPages")).isEqualTo(2);
    }

    @Test
    void aCategoryWithProductsCannotBeDeletedAndDeletedTagsLeaveProducts() {
        String category = owner.post("/api/v1/categories", Map.of("name", "Full")).expectStatus(201).json("$.id");
        String tag = owner.post("/api/v1/tags", Map.of("name", "sale")).expectStatus(201).json("$.id");
        String product = owner.post("/api/v1/products", Map.of("name", "Inside", "price", 1, "categoryId", category,
                "tagIds", List.of(tag))).expectStatus(201).json("$.id");

        owner.delete("/api/v1/categories/" + category).expectError(409, "category.not_empty");
        owner.delete("/api/v1/tags/" + tag).expectStatus(204);

        assertThat(owner.get("/api/v1/products/" + product).<List<Object>>json("$.tags")).isEmpty();
    }

    @Test
    void productDetailsCanBeChangedAndTheCategoryCleared() {
        String category = owner.post("/api/v1/categories", Map.of("name", "Home")).expectStatus(201).json("$.id");
        ApiResponse product = owner.post("/api/v1/products", Map.of("name", "Lamp", "price", 1, "categoryId", category))
                .expectStatus(201);
        String id = product.json("$.id");

        ApiResponse changed = owner.patch("/api/v1/products/" + id, Map.of("name", " Desk  Lamp ",
                "description", "Warm light", "clearCategory", true)).expectStatus(200);

        assertThat(changed.<String>json("$.name")).isEqualTo("Desk Lamp");
        assertThat(changed.<String>json("$.description")).isEqualTo("Warm light");
        assertThat(changed.<String>json("$.categoryId")).isNull();
        owner.patch("/api/v1/products/" + id, Map.of("name", "Stale", "version", 0)).expectError(409, "resource.modified");
    }

    // Fixes from the 3b code review.

    @Test
    void aCombinationComingBackRestoresTheVariantThatWasIt() {
        String id = createWithOptions("Tee", option("Size", "S")).json("$.id");
        ApiResponse colored = replaceOptions(id, option("Size", "S"), option("Color", "Red", "Blue"));
        String red = colored.<List<String>>json("$.variants[?(@.label == 'S / Red')].id").get(0);
        String blue = colored.<List<String>>json("$.variants[?(@.label == 'S / Blue')].id").get(0);
        owner.patch("/api/v1/variants/" + blue, Map.of("price", 77700)).expectStatus(200);
        replaceOptions(id, option("Size", "S"));

        ApiResponse back = replaceOptions(id, option("Size", "S"), option("Color", "Blue"));

        assertThat(back.<List<String>>json("$.variants[?(@.status == 'ACTIVE')].id")).containsExactly(blue);
        assertThat(back.<List<Integer>>json("$.variants[?(@.status == 'ACTIVE')].price")).containsExactly(77700);
        assertThat(back.<List<String>>json("$.variants[?(@.id == '" + red + "')].status")).containsExactly("ARCHIVED");
    }

    @Test
    void anOptionIsContinuedOnceAndRepeatedIdsAreRefused() {
        ApiResponse product = createWithOptions("Pants", option("Size", "S", "M"));
        String id = product.json("$.id");
        String sizeId = product.json("$.options[0].id");
        List<String> before = product.json("$.variants[*].id");

        ApiResponse after = owner.put("/api/v1/products/" + id + "/options", Map.of("options", List.of(
                Map.of("id", sizeId, "name", "Fit", "values", List.of("S", "M")),
                Map.of("name", "Size", "values", List.of("32", "34"))))).expectStatus(200);

        assertThat(after.<List<String>>json("$.options[*].name")).containsExactly("Fit", "Size");
        assertThat(after.<List<String>>json("$.variants[?(@.label == 'S / 32' || @.label == 'M / 32')].id"))
                .containsExactlyElementsOf(before);
        owner.put("/api/v1/products/" + id + "/options", Map.of("options", List.of(
                Map.of("id", sizeId, "name", "A", "values", List.of("1")),
                Map.of("id", sizeId, "name", "B", "values", List.of("2"))))).expectError(400, "validation.failed");
    }

    @Test
    void twoOptionNamesCanBeSwapped() {
        ApiResponse product = createWithOptions("Mixed", option("Size", "S"), option("Color", "Red"));
        String sizeId = product.json("$.options[0].id");
        String colorId = product.json("$.options[1].id");
        String variant = product.json("$.variants[0].id");

        ApiResponse after = owner.put("/api/v1/products/" + product.json("$.id") + "/options", Map.of("options", List.of(
                Map.of("id", sizeId, "name", "Color", "values", List.of("S")),
                Map.of("id", colorId, "name", "Size", "values", List.of("Red"))))).expectStatus(200);

        assertThat(after.<List<String>>json("$.options[*].name")).containsExactly("Color", "Size");
        assertThat(after.<String>json("$.variants[0].id")).isEqualTo(variant);
        assertThat(after.<String>json("$.variants[0].attributes.Color")).isEqualTo("S");
    }

    @Test
    void aPastedNameWithUnusualSpacesStillMatchesItsOption() {
        ApiResponse product = createWithOptions("Pasted", option("Size", "S", "M"));
        List<String> before = product.json("$.variants[*].id");

        ApiResponse after = replaceOptions(product.json("$.id"), option("Size\u00A0", "S", "M"));

        assertThat(after.<List<String>>json("$.variants[*].id")).containsExactlyElementsOf(before);
    }

    @Test
    void onlyVariantsThatFitTheOptionsCanBeRestoredAndOneMustStayActive() {
        ApiResponse product = createWithOptions("Strict", option("Size", "S"), option("Color", "Red", "Blue"));
        String id = product.json("$.id");
        String blue = product.<List<String>>json("$.variants[?(@.label == 'S / Blue')].id").get(0);
        ApiResponse reduced = replaceOptions(id, option("Size", "S"));
        String remaining = reduced.<List<String>>json("$.variants[?(@.status == 'ACTIVE')].id").get(0);

        owner.post("/api/v1/variants/" + blue + "/restore", null).expectError(409, "variant.not_in_options");
        owner.post("/api/v1/variants/" + remaining + "/archive", null).expectError(409, "variant.last_active");
    }

    @Test
    void aRegeneratedSkuKeepsTheOptionOrder() {
        ApiResponse product = createWithOptions("Tee", option("Color", "Black"), option("Size", "L"));
        String sku = product.json("$.variants[0].sku");
        assertThat(sku).isEqualTo("TEE-BLA-L");

        assertThat(owner.patch("/api/v1/variants/" + product.json("$.variants[0].id"), Map.of("sku", ""))
                .expectStatus(200).<String>json("$.sku")).isEqualTo(sku);
    }

    @Test
    void aPageThatIsNotANumberIsAFieldErrorNotAServerError() {
        owner.get("/api/v1/products?page=first").expectError(400, "validation.failed");
        owner.get("/api/v1/business/audit-log?size=many").expectError(400, "validation.failed");
    }

    @Test
    void searchResultsArePagedWithTheirOwnTotals() {
        for (int i = 1; i <= 3; i++) {
            owner.post("/api/v1/products", Map.of("name", "Silk Scarf " + i, "price", 100)).expectStatus(201);
        }
        owner.post("/api/v1/products", Map.of("name", "Leather Wallet", "price", 100)).expectStatus(201);

        ApiResponse first = owner.get("/api/v1/products?q=silk&size=2").expectStatus(200);
        ApiResponse second = owner.get("/api/v1/products?q=silk&size=2&page=2").expectStatus(200);

        assertThat(first.<List<Object>>json("$.items")).hasSize(2);
        assertThat(first.<Integer>json("$.totalItems")).isEqualTo(3);
        assertThat(first.<Integer>json("$.totalPages")).isEqualTo(2);
        assertThat(second.<List<Object>>json("$.items")).hasSize(1);
    }

    // Helpers.

    @SafeVarargs
    private ApiResponse createWithOptions(String name, Map<String, Object>... options) {
        return owner.post("/api/v1/products", Map.of("name", name, "price", 150000, "options", List.of(options)))
                .expectStatus(201);
    }

    @SafeVarargs
    private ApiResponse replaceOptions(String productId, Map<String, Object>... options) {
        return owner.put("/api/v1/products/" + productId + "/options", Map.of("options", List.of(options))).expectStatus(200);
    }

    private static Map<String, Object> option(String name, String... values) {
        return Map.of("name", name, "values", List.of(values));
    }

    private static String statusOf(ApiResponse product, String label) {
        List<String> statuses = product.json("$.variants[?(@.label == '" + label + "')].status");
        return statuses.get(0);
    }

    private ApiClient staffMember() {
        ApiClient staff = new ApiClient(port);
        TestAccounts.Account account = TestAccounts.signUp(staff, messages);
        owner.post("/api/v1/staff/invites", Map.of("to", account.email(), "role", "STAFF")).expectStatus(201);
        String body = messages.to(account.email()).get(messages.to(account.email()).size() - 1).body();
        String token = body.substring(body.indexOf("/invites#") + 9).split("\\s")[0];
        staff.post("/api/v1/invites/accept", Map.of("token", token)).expectStatus(200);
        return staff;
    }

    @Test
    void eachVariantRowKeepsItsOwnPricesAndStockAndNotEveryCombinationIsMade() {
        ApiResponse product = owner.post("/api/v1/products", Map.of("name", "Kurti", "variants", List.of(
                row("Red", "M", 140000, 85000L, 10),
                row("Red", "L", 145000, 85000L, 8),
                row("Blue", "M", 150000, 90000L, 12)))).expectStatus(201);

        assertThat(product.<List<String>>json("$.variants[*].label")).containsExactly("Red / M", "Red / L", "Blue / M");
        assertThat(product.<List<Integer>>json("$.variants[*].price")).containsExactly(140000, 145000, 150000);
        assertThat(product.<List<Integer>>json("$.variants[*].costPrice")).containsExactly(85000, 85000, 90000);
        assertThat(product.<List<Integer>>json("$.variants[*].onHand")).containsExactly(10, 8, 12);
        // No "Blue / L", and each has its own generated SKU.
        assertThat(product.<List<String>>json("$.variants[*].sku")).doesNotHaveDuplicates().hasSize(3);
    }

    @Test
    void aSingleRowMayHaveNoNameOrSizeButSeveralMayNot() {
        ApiResponse plain = owner.post("/api/v1/products", Map.of("name", "Watch", "variants",
                List.of(row("", " ", 500000, null, 3)))).expectStatus(201);
        assertThat(plain.<List<String>>json("$.variants[*].label")).containsExactly("");
        assertThat(plain.<List<Object>>json("$.options")).isEmpty();

        ApiResponse onlySize = owner.post("/api/v1/products", Map.of("name", "Belt", "variants",
                List.of(row("", "M", 50000, null, 1), row(null, "L", 50000, null, 1)))).expectStatus(201);
        assertThat(onlySize.<List<String>>json("$.variants[*].label")).containsExactly("M", "L");

        owner.post("/api/v1/products", Map.of("name", "Bad", "variants",
                List.of(row("Red", "", 100, null, 0), row("", "", 100, null, 0)))).expectStatus(400);
        owner.post("/api/v1/products", Map.of("name", "Bad", "variants", List.of())).expectStatus(400);
        owner.post("/api/v1/products", Map.of("name", "No price")).expectStatus(400);
    }

    @Test
    void theSameNameAndSizeTwiceIsRefusedWhateverTheCase() {
        owner.post("/api/v1/products", Map.of("name", "Kurti", "variants",
                List.of(row("Red", "M", 100, null, 0), row("red ", "m", 200, null, 0))))
                .expectError(409, "variant.duplicate");
    }

    @Test
    void aVariantCanBeAddedRenamedArchivedAndRestored() {
        ApiResponse product = owner.post("/api/v1/products", Map.of("name", "Kurti", "variants",
                List.of(row("Red", "M", 140000, 85000L, 10)))).expectStatus(201);
        String id = product.json("$.id");

        ApiResponse added = owner.post("/api/v1/products/" + id + "/variants", Map.of("variant",
                row("Blue", "L", 150000, 90000L, 4))).expectStatus(201);
        assertThat(added.<List<String>>json("$.variants[*].label")).containsExactly("Red / M", "Blue / L");
        assertThat(added.<List<Integer>>json("$.variants[*].price")).containsExactly(140000, 150000);
        assertThat(added.<List<Integer>>json("$.variants[*].onHand")).containsExactly(10, 4);

        owner.post("/api/v1/products/" + id + "/variants", Map.of("variant", row("BLUE", "l", 1, null, 0)))
                .expectError(409, "variant.duplicate");
        owner.post("/api/v1/products/" + id + "/variants", Map.of("variant", row("", "", 1, null, 0))).expectStatus(400);

        String blue = added.<List<String>>json("$.variants[*].id").get(1);
        owner.patch("/api/v1/variants/" + blue, Map.of("name", "Red", "size", "M")).expectError(409, "variant.duplicate");
        owner.patch("/api/v1/variants/" + blue, Map.of("name", "Navy", "size", "")).expectStatus(200);
        ApiResponse renamed = owner.get("/api/v1/products/" + id);
        assertThat(renamed.<List<String>>json("$.variants[*].label")).containsExactly("Red / M", "Navy");
        // The name, not the SKU, changed; stock and prices stay.
        assertThat(renamed.<List<Integer>>json("$.variants[*].onHand")).containsExactly(10, 4);

        owner.post("/api/v1/variants/" + blue + "/archive", null).expectStatus(200);
        owner.post("/api/v1/variants/" + blue + "/restore", null).expectStatus(200);
    }

    @Test
    void aNameAddedLaterStillReadsBeforeTheSize() {
        ApiResponse product = owner.post("/api/v1/products", Map.of("name", "Belt", "variants",
                List.of(row("", "M", 100, null, 0), row("Red", "L", 100, null, 0)))).expectStatus(201);
        assertThat(product.<List<String>>json("$.variants[*].label")).containsExactly("M", "Red / L");

        ApiResponse sizes = owner.post("/api/v1/products", Map.of("name", "Cap", "variants",
                List.of(row("", "M", 100, null, 0)))).expectStatus(201);
        ApiResponse added = owner.post("/api/v1/products/" + sizes.json("$.id") + "/variants",
                Map.of("variant", row("Blue", "S", 100, null, 0))).expectStatus(201);
        assertThat(added.<List<String>>json("$.variants[*].label")).containsExactly("M", "Blue / S");
    }

    @Test
    void anOlderProductCannotGoOverThreeOptions() {
        ApiResponse product = owner.post("/api/v1/products", Map.of("name", "Old", "price", 100, "options", List.of(
                Map.of("name", "Fit", "values", List.of("Slim")),
                Map.of("name", "Fabric", "values", List.of("Silk"))))).expectStatus(201);

        owner.post("/api/v1/products/" + product.json("$.id") + "/variants",
                Map.of("variant", row("Blue", "S", 100, null, 0))).expectStatus(400);
        // One more option still fits.
        owner.post("/api/v1/products/" + product.json("$.id") + "/variants",
                Map.of("variant", row("Blue", "", 100, null, 0))).expectStatus(201);
    }

    @Test
    void aVariantSaysWhenItIsRunningLow() {
        ApiResponse product = owner.post("/api/v1/products", Map.of("name", "Kurti", "variants",
                List.of(row("Red", "M", 100, null, 20), row("Red", "L", 100, null, 3)))).expectStatus(201);
        assertThat(product.<List<Boolean>>json("$.variants[*].lowStock")).containsExactly(false, true);

        // Its own level counts over the business's; an archived variant is never low.
        List<String> ids = product.json("$.variants[*].id");
        assertThat(owner.patch("/api/v1/variants/" + ids.get(0), Map.of("lowStockThreshold", 25)).expectStatus(200)
                .<Boolean>json("$.lowStock")).isTrue();
        assertThat(owner.post("/api/v1/variants/" + ids.get(1) + "/archive", null).expectStatus(200)
                .<Boolean>json("$.lowStock")).isFalse();
    }

    private static Map<String, Object> row(String name, String size, long price, Long cost, int quantity) {
        Map<String, Object> row = new HashMap<>(Map.of("price", price, "quantity", quantity));
        if (name != null) {
            row.put("name", name);
        }
        if (size != null) {
            row.put("size", size);
        }
        if (cost != null) {
            row.put("costPrice", cost);
        }
        return row;
    }
}
