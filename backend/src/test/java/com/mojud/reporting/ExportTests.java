package com.mojud.reporting;

import static org.assertj.core.api.Assertions.assertThat;

import com.mojud.support.ApiClient;
import com.mojud.support.ApiResponse;
import com.mojud.support.CapturedMessages;
import com.mojud.support.IntegrationTest;
import com.mojud.support.TestAccounts;
import com.mojud.support.TestBusinesses;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

/** CSV exports (implementation-plan.md 9b): files Excel opens correctly, Bangla included. */
@IntegrationTest
class ExportTests {

    static final int MIRPUR = 9027;

    @LocalServerPort
    int port;

    @Autowired
    CapturedMessages messages;

    ApiClient owner;

    @BeforeEach
    void shop() {
        owner = new ApiClient(port);
        TestAccounts.signUp(owner, messages);
        TestBusinesses.create(owner, "Export Shop");
    }

    @Test
    void ordersOpenInExcelAndNoCellIsAFormula() {
        String kurti = owner.post("/api/v1/products", Map.of("name", "Cotton Kurti", "price", 150000, "openingStock", 10,
                "options", List.of(Map.of("name", "Size", "values", List.of("S", "M"))))).expectStatus(201).json("$.variants[0].id");
        String customer = owner.post("/api/v1/customers", Map.of("name", "=HYPERLINK(\"http://x\",\"click\")",
                "phone", "01712345678", "address", "House 4, \"Green\" Road", "areaId", MIRPUR)).expectStatus(201).json("$.id");
        String id = owner.post("/api/v1/orders", Map.of("customerId", customer, "items", List.of(Map.of("variantId", kurti,
                "quantity", 2)), "discount", 10000, "note", "Line one\nline two")).expectStatus(201).json("$.id");
        owner.post("/api/v1/orders/" + id + "/confirm", null).expectStatus(200);
        owner.post("/api/v1/orders/" + id + "/ship", Map.of("courier", "Pathao", "trackingNo", "PTH-1")).expectStatus(200);

        HttpResponse<byte[]> csv = owner.download("/api/v1/exports/orders.csv");

        assertThat(csv.statusCode()).isEqualTo(200);
        assertThat(csv.headers().firstValue("Content-Type")).hasValue("text/csv;charset=UTF-8");
        assertThat(csv.headers().firstValue("Content-Disposition")).hasValueSatisfying(v -> assertThat(v).contains("orders-"));
        assertThat(csv.body()).startsWith(0xEF, 0xBB, 0xBF);
        String text = new String(csv.body(), StandardCharsets.UTF_8);
        assertThat(text).contains("\r\n");
        List<String> lines = text.substring(1).lines().toList();
        assertThat(lines.get(0)).startsWith("Order,Date,Status,Payment,Customer,Customer phone,");
        assertThat(lines.get(1)).startsWith("#1001,").contains(",SHIPPED,UNPAID,")
                .contains(",\"'=HYPERLINK(\"\"http://x\"\",\"\"click\"\")\",01712-345678,")
                .contains(",\"House 4, \"\"Green\"\" Road\",Mirpur,Dhaka,Cotton Kurti S x2,3000.00,100.00,70.00,2970.00,0.00,2970.00,")
                .contains(",Pathao,PTH-1,\"Line one");
        assertThat(lines.get(2)).isEqualTo("line two\"");
    }

    @Test
    void customersInBangla() {
        owner.post("/api/v1/customers", Map.of("name", "নাসরিন আক্তার", "phone", "01911223344", "areaId", MIRPUR,
                "note", "+ always call first")).expectStatus(201);
        owner.patch("/api/v1/me", Map.of("locale", "bn")).expectStatus(200);

        String text = new String(owner.download("/api/v1/exports/customers.csv").body(), StandardCharsets.UTF_8);

        List<String> lines = text.substring(1).lines().toList();
        assertThat(lines.get(0)).isEqualTo("নাম,ফোন,অন্য ফোন,ঠিকানা,এলাকা,জেলা,অর্ডার,শেষ অর্ডার,নোট,গ্রাহক হয়েছেন");
        assertThat(lines.get(1)).startsWith("নাসরিন আক্তার,01911-223344,,,মিরপুর,ঢাকা,0,,'+ always call first,");
    }

    @Test
    void productsExportImportsBackUnchanged() {
        String women = owner.post("/api/v1/categories", Map.of("name", "Women")).expectStatus(201).json("$.id");
        String kurtis = owner.post("/api/v1/categories", Map.of("name", "Kurti", "parentId", women)).expectStatus(201).json("$.id");
        owner.post("/api/v1/products", Map.of("name", "Cotton Kurti", "price", 150000, "costPrice", 90000, "openingStock", 7,
                "categoryId", kurtis, "description", "Soft cotton, hand-stitched",
                "options", List.of(Map.of("name", "Size", "values", List.of("S", "M"))))).expectStatus(201);
        String wallet = owner.post("/api/v1/products", Map.of("name", "Leather Wallet", "price", 95000, "sku", "WAL-1",
                "openingStock", 3)).expectStatus(201).json("$.variants[0].id");
        owner.patch("/api/v1/variants/" + wallet, Map.of("lowStockThreshold", 2)).expectStatus(200);
        owner.post("/api/v1/products", Map.of("name", "Silk Scarf", "price", 50050, "openingStock", 0,
                "options", List.of(Map.of("name", "Color", "values", List.of("Red"))))).expectStatus(201);

        byte[] csv = owner.download("/api/v1/exports/products.csv").body();
        List<String> lines = new String(csv, StandardCharsets.UTF_8).substring(1).lines().toList();
        assertThat(lines.get(0)).isEqualTo("Product name,SKU,Price,Cost price,Stock,Category,Color,Size,Description,"
                + "Low stock alert,Reserved,Available,Status");
        assertThat(lines).contains("Cotton Kurti,COTTONKU-S,1500.00,900.00,7,Women > Kurti,,S,\"Soft cotton, hand-stitched\",,0,7,ACTIVE",
                "Leather Wallet,WAL-1,950.00,,3,,,,,2,0,3,ACTIVE", "Silk Scarf,SILKSCAR-RED,500.50,,0,,Red,,,,0,0,ACTIVE");

        // Option columns: by position, then name (both options are first on their products).
        // Back in through the importer: every column is recognised, and nothing changes.
        ApiResponse job = owner.upload("/api/v1/imports", "products.csv", csv).expectStatus(201);
        assertThat(job.<Map<String, Integer>>json("$.totals")).containsEntry("valid", 4).containsEntry("update", 4);
        ApiResponse done = owner.post("/api/v1/imports/" + job.json("$.id") + "/commit", null).expectStatus(202);
        ApiResponse after = owner.get("/api/v1/imports/" + done.json("$.id"));
        assertThat(after.<Map<String, Integer>>json("$.progress")).containsEntry("unchanged", 4).containsEntry("updated", 0)
                .containsEntry("created", 0);
    }
}
