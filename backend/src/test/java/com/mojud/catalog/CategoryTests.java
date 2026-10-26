package com.mojud.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import com.mojud.support.ApiClient;
import com.mojud.support.ApiResponse;
import com.mojud.support.CapturedMessages;
import com.mojud.support.IntegrationTest;
import com.mojud.support.TestAccounts;
import com.mojud.support.TestBusinesses;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

/** Categories and tags (implementation-plan.md 3a). */
@IntegrationTest
class CategoryTests {

    @LocalServerPort
    int port;

    @Autowired
    CapturedMessages messages;

    ApiClient owner;

    @BeforeEach
    void business() {
        owner = new ApiClient(port);
        TestAccounts.signUp(owner, messages);
        TestBusinesses.create(owner, "Catalog Shop");
    }

    // Categories.

    @Test
    void categoriesFormATwoLevelTreeInOrder() {
        String women = create("Women", null);
        String men = create("Men", null);
        create("Kurti", women);
        create("Saree", women);
        create("Punjabi", men);

        ApiResponse tree = owner.get("/api/v1/categories").expectStatus(200);

        assertThat(tree.<List<String>>json("$[*].name")).containsExactly("Women", "Men");
        assertThat(tree.<List<String>>json("$[0].children[*].name")).containsExactly("Kurti", "Saree");
        assertThat(tree.<List<String>>json("$[1].children[*].name")).containsExactly("Punjabi");
    }

    @Test
    void aThirdLevelIsRefused() {
        String women = create("Women", null);
        String kurti = create("Kurti", women);

        owner.post("/api/v1/categories", Map.of("name", "Cotton", "parentId", kurti))
                .expectError(409, "category.too_deep");
    }

    @Test
    void aCategoryWithSubcategoriesCannotBecomeOne() {
        String women = create("Women", null);
        create("Kurti", women);
        String sale = create("Sale", null);

        owner.post("/api/v1/categories/" + women + "/move", Map.of("parentId", sale))
                .expectError(409, "category.too_deep");
        owner.post("/api/v1/categories/" + sale + "/move", Map.of("parentId", sale))
                .expectError(409, "category.too_deep");
    }

    @Test
    void categoriesMoveBetweenGroupsToTheEnd() {
        String women = create("Women", null);
        String men = create("Men", null);
        create("Punjabi", men);
        String kurti = create("Kurti", women);

        owner.post("/api/v1/categories/" + kurti + "/move", Map.of("parentId", men)).expectStatus(200);
        String sale = create("Sale", women);
        owner.post("/api/v1/categories/" + sale + "/move", Map.of()).expectStatus(200);

        ApiResponse tree = owner.get("/api/v1/categories");
        assertThat(tree.<List<String>>json("$[*].name")).containsExactly("Women", "Men", "Sale");
        assertThat(tree.<List<String>>json("$[1].children[*].name")).containsExactly("Punjabi", "Kurti");
    }

    @Test
    void namesAreUniqueWithinAGroupIgnoringCase() {
        String women = create("Women", null);
        String men = create("Men", null);
        create("Kurti", women);

        owner.post("/api/v1/categories", Map.of("name", "  KURTI ", "parentId", women))
                .expectError(409, "category.name_taken");
        owner.post("/api/v1/categories", Map.of("name", "women")).expectError(409, "category.name_taken");
        create("Kurti", men);
        owner.patch("/api/v1/categories/" + men, Map.of("name", "WOMEN")).expectError(409, "category.name_taken");
    }

    @Test
    void namesAreTidiedAndBanglaComparesByMeaning() {
        String composed = "বোরকা";
        String decomposed = "বোরকা";

        ApiResponse created = owner.post("/api/v1/categories", Map.of("name", "  " + composed + "   collection "))
                .expectStatus(201);

        assertThat(created.<String>json("$.name")).isEqualTo(composed + " collection");
        owner.post("/api/v1/categories", Map.of("name", decomposed + " collection"))
                .expectError(409, "category.name_taken");
    }

    @Test
    void aGroupIsReorderedByListingAllOfItsCategories() {
        String a = create("A", null);
        String b = create("B", null);
        String c = create("C", null);

        owner.put("/api/v1/categories/order", Map.of("ids", List.of(c, a, b))).expectStatus(204);

        assertThat(owner.get("/api/v1/categories").<List<String>>json("$[*].name")).containsExactly("C", "A", "B");
        owner.put("/api/v1/categories/order", Map.of("ids", List.of(c, a))).expectError(400, "validation.failed");
        owner.put("/api/v1/categories/order", Map.of("ids", List.of(c, a, a))).expectError(400, "validation.failed");
    }

    @Test
    void onlyAnEmptyCategoryCanBeDeleted() {
        String women = create("Women", null);
        String kurti = create("Kurti", women);

        owner.delete("/api/v1/categories/" + women).expectError(409, "category.not_empty");
        owner.delete("/api/v1/categories/" + kurti).expectStatus(204);
        owner.delete("/api/v1/categories/" + women).expectStatus(204);

        assertThat(owner.get("/api/v1/categories").<List<Object>>json("$")).isEmpty();
    }

    @Test
    void staffReadButOnlyManagersChange() {
        create("Women", null);
        ApiClient staff = new ApiClient(port);
        TestAccounts.Account account = TestAccounts.signUp(staff, messages);
        owner.post("/api/v1/staff/invites", Map.of("to", account.email(), "role", "STAFF")).expectStatus(201);
        String body = messages.to(account.email()).get(messages.to(account.email()).size() - 1).body();
        String token = body.substring(body.indexOf("/invites#") + 9).split("\\s")[0];
        staff.post("/api/v1/invites/accept", Map.of("token", token)).expectStatus(200);

        staff.get("/api/v1/categories").expectStatus(200);
        staff.get("/api/v1/tags").expectStatus(200);
        staff.post("/api/v1/categories", Map.of("name", "Men")).expectError(403, "access.denied");
        staff.post("/api/v1/tags", Map.of("name", "eid")).expectError(403, "access.denied");
    }

    @Test
    void categoryNamesAreValidated() {
        owner.post("/api/v1/categories", Map.of("name", " ")).expectError(400, "validation.failed");
        owner.post("/api/v1/categories", Map.of("name", "x".repeat(81))).expectError(400, "validation.failed");
    }

    // Tags.

    @Test
    void tagsAreUniqueIgnoringCaseAndListedByName() {
        String eid = owner.post("/api/v1/tags", Map.of("name", "Eid")).expectStatus(201).json("$.id");
        owner.post("/api/v1/tags", Map.of("name", "new arrival")).expectStatus(201);

        owner.post("/api/v1/tags", Map.of("name", "EID")).expectError(409, "tag.name_taken");
        owner.patch("/api/v1/tags/" + eid, Map.of("name", "Eid 2026")).expectStatus(200);
        assertThat(owner.get("/api/v1/tags").<List<String>>json("$[*].name")).containsExactly("Eid 2026", "new arrival");

        owner.delete("/api/v1/tags/" + eid).expectStatus(204);
        assertThat(owner.get("/api/v1/tags").<List<String>>json("$[*].name")).containsExactly("new arrival");
    }

    private String create(String name, String parentId) {
        Map<String, Object> body = parentId == null ? Map.of("name", name) : Map.of("name", name, "parentId", parentId);
        return owner.post("/api/v1/categories", body).expectStatus(201).json("$.id");
    }
}
