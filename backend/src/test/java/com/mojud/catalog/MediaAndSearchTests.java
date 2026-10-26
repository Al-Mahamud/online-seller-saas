package com.mojud.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import com.mojud.support.ApiClient;
import com.mojud.support.ApiResponse;
import com.mojud.support.CapturedMessages;
import com.mojud.support.IntegrationTest;
import com.mojud.support.TestAccounts;
import com.mojud.support.TestBusinesses;
import com.mojud.support.TestImages;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

/** Uploads, product images, the logo and product search (implementation-plan.md 3c). */
@IntegrationTest
class MediaAndSearchTests {

    @LocalServerPort
    int port;

    @Autowired
    CapturedMessages messages;

    ApiClient owner;

    @BeforeEach
    void business() {
        owner = new ApiClient(port);
        TestAccounts.signUp(owner, messages);
        TestBusinesses.create(owner, "Media Shop");
    }

    // Uploads.

    @Test
    void aPngNamedJpgIsStoredAsPngAndServedToMembers() {
        ApiResponse uploaded = owner.upload("/api/v1/uploads", "photo.jpg", TestImages.png(1600, 1200)).expectStatus(201);

        HttpResponse<byte[]> large = owner.download(uploaded.json("$.links.large"));
        HttpResponse<byte[]> small = owner.download(uploaded.json("$.links.small"));

        assertThat(uploaded.<Integer>json("$.width")).isEqualTo(800);
        assertThat(large.statusCode()).isEqualTo(200);
        assertThat(large.headers().firstValue("Content-Type")).hasValue("image/jpeg");
        assertThat(large.headers().firstValue("Cache-Control")).get().asString().contains("private").contains("immutable");
        assertThat(small.body().length).isLessThan(large.body().length);
    }

    @Test
    void aProgramNamedJpgIsRefused() {
        owner.upload("/api/v1/uploads", "cat.jpg", "MZ this is a program".getBytes())
                .expectError(415, "file.unsupported");
    }

    @Test
    void filesOver8MbAreRefused() {
        owner.upload("/api/v1/uploads", "huge.jpg", new byte[9 * 1024 * 1024]).expectError(413, "file.too_large");
    }

    @Test
    void staffViewImagesButDoNotUpload() {
        String large = owner.upload("/api/v1/uploads", "a.png", TestImages.png(400, 400)).expectStatus(201).json("$.links.large");
        ApiClient staff = staff();

        assertThat(staff.download(large).statusCode()).isEqualTo(200);
        staff.upload("/api/v1/uploads", "b.png", TestImages.png(400, 400)).expectError(403, "access.denied");
        assertThat(new ApiClient(port).download(large).statusCode()).isEqualTo(401);
    }

    // Product images.

    @Test
    void productsShowUpToEightImagesInOrderSomeForAVariant() {
        ApiResponse product = owner.post("/api/v1/products", Map.of("name", "Photo Kurti", "price", 1000,
                "options", List.of(Map.of("name", "Color", "values", List.of("Red", "Blue"))))).expectStatus(201);
        String id = product.json("$.id");
        String blue = product.json("$.variants[1].id");
        String first = upload();
        String second = upload();

        owner.post("/api/v1/products/" + id + "/images", Map.of("fileId", first)).expectStatus(200);
        ApiResponse withTwo = owner.post("/api/v1/products/" + id + "/images", Map.of("fileId", second, "variantId", blue))
                .expectStatus(200);
        List<String> imageIds = withTwo.json("$.images[*].id");
        ApiResponse reordered = owner.put("/api/v1/products/" + id + "/images/order",
                Map.of("ids", List.of(imageIds.get(1), imageIds.get(0)))).expectStatus(200);

        assertThat(reordered.<List<String>>json("$.images[*].fileId")).containsExactly(second, first);
        assertThat(reordered.<String>json("$.images[0].variantId")).isEqualTo(blue);
        assertThat(reordered.<String>json("$.images[0].small")).endsWith("/" + second + "/small");
        assertThat(owner.get("/api/v1/products").<String>json("$.items[0].thumbnail")).endsWith("/" + second + "/small");

        owner.patch("/api/v1/products/" + id + "/images/" + imageIds.get(1), Map.of()).expectStatus(200);
        ApiResponse removed = owner.delete("/api/v1/products/" + id + "/images/" + imageIds.get(0)).expectStatus(200);
        assertThat(removed.<List<String>>json("$.images[*].fileId")).containsExactly(second);
        assertThat(removed.<Object>json("$.images[0].variantId")).isNull();
    }

    @Test
    void aProductTakesAtMostEightImagesAndOnlyItsOwnVariants() {
        String id = owner.post("/api/v1/products", Map.of("name", "Busy", "price", 1)).expectStatus(201).json("$.id");
        String otherVariant = owner.post("/api/v1/products", Map.of("name", "Other", "price", 1)).expectStatus(201)
                .json("$.variants[0].id");
        for (int i = 0; i < 8; i++) {
            owner.post("/api/v1/products/" + id + "/images", Map.of("fileId", upload())).expectStatus(200);
        }

        owner.post("/api/v1/products/" + id + "/images", Map.of("fileId", upload())).expectError(409, "product.image_limit");
        String anotherId = owner.post("/api/v1/products", Map.of("name", "Third", "price", 1)).expectStatus(201).json("$.id");
        owner.post("/api/v1/products/" + anotherId + "/images", Map.of("fileId", upload(), "variantId", otherVariant))
                .expectError(404, "resource.not_found");
    }

    // Logo.

    @Test
    void anUploadedImageBecomesTheLogo() {
        String file = upload();

        ApiResponse business = owner.put("/api/v1/business/logo", Map.of("fileId", file)).expectStatus(200);

        assertThat(business.<String>json("$.logo.small")).endsWith("/" + file + "/small");
        assertThat(owner.delete("/api/v1/business/logo").expectStatus(200).<Object>json("$.logo")).isNull();
    }

    // Search.

    @Test
    void searchFindsPartsOfNamesSmallTyposBanglaAndSkus() {
        owner.post("/api/v1/products", Map.of("name", "Cotton Kurti", "price", 1, "sku", "CK-100")).expectStatus(201);
        owner.post("/api/v1/products", Map.of("name", "লাল জামদানি শাড়ি", "price", 1)).expectStatus(201);
        owner.post("/api/v1/products", Map.of("name", "Leather Wallet", "price", 1)).expectStatus(201);

        assertThat(names("kurt")).containsExactly("Cotton Kurti");
        assertThat(names("kurty")).containsExactly("Cotton Kurti");
        assertThat(names("জামদানি")).containsExactly("লাল জামদানি শাড়ি");
        assertThat(names("ck-1")).containsExactly("Cotton Kurti");
        assertThat(names("%")).isEmpty();
        assertThat(names("watch")).isEmpty();
    }

    private List<String> names(String q) {
        return owner.get("/api/v1/products?q=" + java.net.URLEncoder.encode(q, java.nio.charset.StandardCharsets.UTF_8))
                .expectStatus(200).json("$.items[*].name");
    }

    private String upload() {
        return owner.upload("/api/v1/uploads", "p.jpg", TestImages.jpeg(600, 600)).expectStatus(201).json("$.id");
    }

    private ApiClient staff() {
        ApiClient staff = new ApiClient(port);
        TestAccounts.Account account = TestAccounts.signUp(staff, messages);
        owner.post("/api/v1/staff/invites", Map.of("to", account.email(), "role", "STAFF")).expectStatus(201);
        String body = messages.to(account.email()).get(messages.to(account.email()).size() - 1).body();
        String token = body.substring(body.indexOf("/invites#") + 9).split("\\s")[0];
        staff.post("/api/v1/invites/accept", Map.of("token", token)).expectStatus(200);
        return staff;
    }
}
