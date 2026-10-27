package com.mojud.orders;

import static org.assertj.core.api.Assertions.assertThat;

import com.mojud.shared.db.system.SystemDatabase;
import com.mojud.support.ApiClient;
import com.mojud.support.CapturedMessages;
import com.mojud.support.IntegrationTest;
import com.mojud.support.TestAccounts;
import com.mojud.support.TestImages;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Packing slips (implementation-plan.md 8d): the HTML for a fixed order, in English and Bangla,
 * against reviewed snapshots in src/test/resources/snapshots. Missing snapshots are written and
 * the test fails, to be read and committed; {@code -DupdateSnapshots=true} rewrites them.
 */
@IntegrationTest
class PackingSlipTests {

    static final Path SNAPSHOTS = Path.of("src/test/resources/snapshots");
    static final int MIRPUR = 9027;
    static final Instant ORDERED = Instant.parse("2026-10-28T05:30:00Z");

    @LocalServerPort
    int port;

    @Autowired
    CapturedMessages messages;

    @Autowired
    SystemDatabase systemDatabase;

    ApiClient owner;
    String customerId;
    String kurtiS;
    String wallet;

    @BeforeEach
    void shop() {
        owner = new ApiClient(port);
        TestAccounts.signUp(owner, messages);
        owner.post("/api/v1/businesses", Map.of("name", "Rahima's Kurti House", "phone", "01712345678",
                "address", "House 12, Road 5, Dhanmondi")).expectStatus(201);
        String logo = owner.upload("/api/v1/uploads", "logo.png", TestImages.png(300, 300)).expectStatus(201).json("$.id");
        owner.put("/api/v1/business/logo", Map.of("fileId", logo)).expectStatus(200);
        owner.patch("/api/v1/business/settings", Map.of("invoiceFooter",
                "Thank you for shopping with us!\nExchange within 3 days.")).expectStatus(200);
        kurtiS = owner.post("/api/v1/products", Map.of("name", "Cotton Kurti", "price", 150000, "openingStock", 10,
                "options", List.of(Map.of("name", "Size", "values", List.of("S", "M"))))).expectStatus(201)
                .json("$.variants[0].id");
        wallet = owner.post("/api/v1/products", Map.of("name", "Leather Wallet <Gift> & Co", "price", 95000, "sku", "WALLET-01",
                "openingStock", 10)).expectStatus(201).json("$.variants[0].id");
        customerId = owner.post("/api/v1/customers", Map.of("name", "Nasrin Akter", "phone", "01911223344",
                "address", "Flat 3B, Road 11, Section 6", "areaId", MIRPUR)).expectStatus(201).json("$.id");
    }

    @Test
    void theSlipMatchesItsSnapshotInEnglishAndBangla() throws IOException {
        String id = fixedOrder();

        for (String lang : List.of("en", "bn")) {
            HttpResponse<byte[]> slip = owner.download("/api/v1/orders/" + id + "/slip?lang=" + lang);
            assertThat(slip.statusCode()).isEqualTo(200);
            String html = new String(slip.body(), StandardCharsets.UTF_8);
            assertThat(html).as("the logo is embedded").containsPattern("src=\"data:image/(png|jpeg);base64,[A-Za-z0-9+/=]{100,}\"");
            matchesSnapshot("packing-slip-" + lang + ".html", html.replaceAll("data:image/(png|jpeg);base64,[A-Za-z0-9+/=]+", "data:LOGO"));
        }
    }

    @Test
    void theSlipIsAPageWithItsOwnPolicy() {
        String id = fixedOrder();

        HttpResponse<byte[]> slip = owner.download("/api/v1/orders/" + id + "/slip");

        assertThat(slip.headers().firstValue("Content-Type")).hasValue("text/html;charset=UTF-8");
        assertThat(slip.headers().allValues("Content-Security-Policy")).containsExactly(PackingSlips.CONTENT_SECURITY_POLICY);
        assertThat(slip.headers().firstValue("Cache-Control")).hasValueSatisfying(v -> assertThat(v).contains("no-store"));
        String html = new String(slip.body(), StandardCharsets.UTF_8);
        assertThat(html).doesNotContain("<script>");
        assertThat(new String(owner.download("/api/v1/orders/" + id + "/slip?autoprint=true").body(), StandardCharsets.UTF_8))
                .contains("<script>" + PackingSlips.PRINT_SCRIPT + "</script>");
    }

    @Test
    void manySlipsPrintTogetherInTheOrderGiven() {
        String first = fixedOrder();
        String second = owner.post("/api/v1/orders", Map.of("customerId", customerId,
                "items", List.of(Map.of("variantId", wallet, "quantity", 1)))).expectStatus(201).json("$.id");

        String html = new String(owner.download("/api/v1/orders/print?ids=" + second + "," + first + "&lang=en").body(),
                StandardCharsets.UTF_8);

        assertThat(html.split("<section class=\"slip\">")).hasSize(3);
        assertThat(html.indexOf("#1002")).isLessThan(html.indexOf("#1001"));
        assertThat(owner.download("/api/v1/orders/print?ids=" + first + "," + UUID.randomUUID()).statusCode()).isEqualTo(404);
    }

    @Test
    void aPrepaidOrderTellsTheCourierToCollectNothing() {
        String id = owner.post("/api/v1/orders", Map.of("customerId", customerId, "advancePaid", 102000,
                "items", List.of(Map.of("variantId", wallet, "quantity", 1)))).expectStatus(201).json("$.id");

        String html = new String(owner.download("/api/v1/orders/" + id + "/slip?lang=en").body(), StandardCharsets.UTF_8);

        assertThat(html).contains("<span>Paid in full: collect nothing</span><strong>৳0</strong>");
    }

    /** Ordered 28 Oct 2026: 2 × Cotton Kurti S, 1 × wallet, ৳100 off, ৳500 bKash advance, shipped by Pathao. */
    private String fixedOrder() {
        String id = owner.post("/api/v1/orders", Map.of("customerId", customerId, "items", List.of(
                Map.of("variantId", kurtiS, "quantity", 2), Map.of("variantId", wallet, "quantity", 1)),
                "discount", 10000, "advancePaid", 50000, "advanceMethod", "BKASH",
                "note", "Call before delivery. Gate code 1234.")).expectStatus(201).json("$.id");
        owner.post("/api/v1/orders/" + id + "/confirm", null).expectStatus(200);
        owner.post("/api/v1/orders/" + id + "/ship", Map.of("courier", "Pathao", "trackingNo", "PTH-77812")).expectStatus(200);
        systemDatabase.jdbc().sql("update orders set created_at = ?, confirmed_at = ? where id = ?")
                .params(Timestamp.from(ORDERED), Timestamp.from(ORDERED.plusSeconds(600)), UUID.fromString(id)).update();
        return id;
    }

    private static void matchesSnapshot(String name, String actual) throws IOException {
        Path file = SNAPSHOTS.resolve(name);
        if (Boolean.getBoolean("updateSnapshots") || !Files.exists(file)) {
            Files.createDirectories(SNAPSHOTS);
            Files.writeString(file, actual, StandardCharsets.UTF_8);
            throw new AssertionError("Snapshot " + file + " written: read it, then commit it");
        }
        assertThat(actual).isEqualTo(Files.readString(file, StandardCharsets.UTF_8));
    }
}
