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
        owner.post("/api/v1/categories/" + sale + "/move", Map.of("topLevel", true)).expectStatus(200);

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
    void eachCategoryAndTagSaysHowManyProductsItHas() {
        String women = create("Women", null);
        String kurti = create("Kurti", women);
        String bags = create("Bags", null);
        String eid = owner.post("/api/v1/tags", Map.of("name", "Eid")).expectStatus(201).json("$.id");
        owner.post("/api/v1/tags", Map.of("name", "unused")).expectStatus(201);
        owner.post("/api/v1/products", Map.of("name", "One", "price", 1, "categoryId", kurti, "tagIds", List.of(eid)))
                .expectStatus(201);
        owner.post("/api/v1/products", Map.of("name", "Two", "price", 1, "categoryId", kurti, "tagIds", List.of(eid)))
                .expectStatus(201);
        String archived = owner.post("/api/v1/products", Map.of("name", "Three", "price", 1, "categoryId", women))
                .expectStatus(201).json("$.id");
        owner.post("/api/v1/products/" + archived + "/archive", Map.of()).expectStatus(200);

        ApiResponse tree = owner.get("/api/v1/categories").expectStatus(200);
        // Directly in each, archived too: they also keep a category from being deleted.
        assertThat(tree.<List<Integer>>json("$[*].productCount")).containsExactly(1, 0);
        assertThat(tree.<List<Integer>>json("$[0].children[*].productCount")).containsExactly(2);
        assertThat(owner.get("/api/v1/tags").<List<Integer>>json("$[*].productCount")).containsExactly(2, 0);

        // What a change answers carries the count too.
        assertThat(owner.patch("/api/v1/categories/" + kurti, Map.of("name", "Kurtis")).expectStatus(200)
                .<Integer>json("$.productCount")).isEqualTo(2);
        assertThat(owner.post("/api/v1/categories/" + kurti + "/move", Map.of("topLevel", true)).expectStatus(200)
                .<Integer>json("$.productCount")).isEqualTo(2);
        assertThat(owner.patch("/api/v1/tags/" + eid, Map.of("name", "Eid 2026")).expectStatus(200)
                .<Integer>json("$.productCount")).isEqualTo(2);
        assertThat(owner.post("/api/v1/categories", Map.of("name", "New", "parentId", bags)).expectStatus(201)
                .<Integer>json("$.productCount")).isZero();
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

    // Fixes from the 3a code review.

    @Test
    void twoOppositeMovesAtOnceCannotMakeALoop() throws Exception {
        String a = create("A", null);
        String b = create("B", null);

        List<ApiResponse> answers = inParallel(
                () -> owner.post("/api/v1/categories/" + a + "/move", Map.of("parentId", b)),
                () -> owner.post("/api/v1/categories/" + b + "/move", Map.of("parentId", a)));

        assertThat(answers).extracting(ApiResponse::status).containsExactlyInAnyOrder(200, 409);
        assertThat(visibleCount()).isEqualTo(2);
    }

    @Test
    void addingASubcategoryWhileItsParentMovesCannotMakeAThirdLevel() throws Exception {
        String x = create("X", null);
        String y = create("Y", null);

        List<ApiResponse> answers = inParallel(
                () -> owner.post("/api/v1/categories", Map.of("name", "Child", "parentId", x)),
                () -> owner.post("/api/v1/categories/" + x + "/move", Map.of("parentId", y)));

        long created = answers.stream().filter(answer -> answer.status() / 100 == 2).count();
        assertThat(created).isEqualTo(1);
        assertThat(visibleCount()).isEqualTo(answers.get(0).status() == 201 ? 3 : 2);
    }

    @Test
    void categoriesCreatedTogetherGetTheirOwnPositions() throws Exception {
        List<ApiResponse> answers = inParallel(
                () -> owner.post("/api/v1/categories", Map.of("name", "One")),
                () -> owner.post("/api/v1/categories", Map.of("name", "Two")),
                () -> owner.post("/api/v1/categories", Map.of("name", "Three")),
                () -> owner.post("/api/v1/categories", Map.of("name", "Four")));

        assertThat(answers).extracting(ApiResponse::status).containsOnly(201);
        assertThat(owner.get("/api/v1/categories").<List<Integer>>json("$[*].position"))
                .containsExactlyInAnyOrder(0, 1, 2, 3);
    }

    @Test
    void movingIntoItsOwnGroupChangesNothingAndTheTargetMustBeExplicit() {
        String women = create("Women", null);
        String kurti = create("Kurti", women);
        create("Saree", women);

        owner.post("/api/v1/categories/" + kurti + "/move", Map.of("parentId", women)).expectStatus(200);
        assertThat(owner.get("/api/v1/categories").<List<String>>json("$[0].children[*].name"))
                .containsExactly("Kurti", "Saree");

        owner.post("/api/v1/categories/" + kurti + "/move", Map.of()).expectError(400, "validation.failed");
        owner.post("/api/v1/categories/" + kurti + "/move", Map.of("parentId", women, "topLevel", true))
                .expectError(400, "validation.failed");
        assertThat(owner.get("/api/v1/categories").<List<String>>json("$[*].name")).containsExactly("Women");
    }

    @Test
    void aSubcategoryHasNoGroupToReorder() {
        String women = create("Women", null);
        String kurti = create("Kurti", women);

        owner.put("/api/v1/categories/order", Map.of("parentId", kurti, "ids", List.of()))
                .expectError(409, "category.too_deep");
    }

    @Test
    void unusualSpacesCountAsSpaces() {
        owner.post("/api/v1/tags", Map.of("name", "Eid Sale")).expectStatus(201);

        owner.post("/api/v1/tags", Map.of("name", "Eid\u00A0Sale")).expectError(409, "tag.name_taken");
        owner.post("/api/v1/tags", Map.of("name", "Eid\u2003 Sale")).expectError(409, "tag.name_taken");
        assertThat(owner.post("/api/v1/tags", Map.of("name", "\u00A0New\u00A0\u00A0arrival\u00A0")).expectStatus(201)
                .<String>json("$.name")).isEqualTo("New arrival");
        owner.post("/api/v1/tags", Map.of("name", "\u00A0\u00A0")).expectError(400, "validation.failed");
    }

    private int visibleCount() {
        ApiResponse tree = owner.get("/api/v1/categories").expectStatus(200);
        return tree.<List<Object>>json("$[*].id").size() + tree.<List<Object>>json("$[*].children[*].id").size();
    }

    @SafeVarargs
    private List<ApiResponse> inParallel(java.util.concurrent.Callable<ApiResponse>... calls) throws Exception {
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(calls.length);
        try {
            java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
            List<java.util.concurrent.Future<ApiResponse>> futures = new java.util.ArrayList<>();
            for (java.util.concurrent.Callable<ApiResponse> call : calls) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return call.call();
                }));
            }
            start.countDown();
            List<ApiResponse> answers = new java.util.ArrayList<>();
            for (java.util.concurrent.Future<ApiResponse> future : futures) {
                answers.add(future.get(60, java.util.concurrent.TimeUnit.SECONDS));
            }
            return answers;
        } finally {
            pool.shutdownNow();
        }
    }

    private String create(String name, String parentId) {
        Map<String, Object> body = parentId == null ? Map.of("name", name) : Map.of("name", name, "parentId", parentId);
        return owner.post("/api/v1/categories", body).expectStatus(201).json("$.id");
    }
}
