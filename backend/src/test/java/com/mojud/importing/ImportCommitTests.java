package com.mojud.importing;

import static org.assertj.core.api.Assertions.assertThat;

import com.mojud.importing.system.ImportSweeper;
import com.mojud.shared.db.system.SystemDatabase;
import com.mojud.support.ApiClient;
import com.mojud.support.ApiResponse;
import com.mojud.support.CapturedMessages;
import com.mojud.support.IntegrationTest;
import com.mojud.support.TestAccounts;
import com.mojud.support.TestBusinesses;
import com.mojud.support.TestClock;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Committing imports (implementation-plan.md 5b). In tests the import runs inline once the commit
 * request's transaction commits, so the response comes after the import has finished.
 */
@IntegrationTest
class ImportCommitTests {

    @LocalServerPort
    int port;

    @Autowired
    CapturedMessages messages;

    @Autowired
    SystemDatabase systemDatabase;

    @Autowired
    ImportSweeper sweeper;

    @Autowired
    TestClock clock;

    ApiClient owner;
    UUID business;

    @BeforeEach
    void business() {
        owner = new ApiClient(port);
        TestAccounts.signUp(owner, messages);
        business = TestBusinesses.create(owner, "Commit Shop");
    }

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    /** 2,000 rows: 501 products in sizes S, M, L, XL; every 54th row (37 of them) has a bad price. */
    static byte[] twoThousandRows() {
        StringBuilder csv = new StringBuilder("Name,Price,Stock,Size,Category\n");
        for (int i = 1; i <= 2000; i++) {
            csv.append("Item ").append(i / 4).append(',').append(i % 54 == 0 ? "ask" : Integer.toString(100 + i % 50))
                    .append(',').append(i % 7).append(',').append(new String[] {"S", "M", "L", "XL"}[i % 4])
                    .append(",Bulk > Items\n");
        }
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void twoThousandRowsImportBadRowsAreReportedAndASecondImportChangesNothing() {
        long start = System.nanoTime();
        ApiResponse first = upload("bulk.csv", twoThousandRows());
        ApiResponse done = commit(first);
        long millis = (System.nanoTime() - start) / 1_000_000;

        assertThat(done.<String>json("$.status")).isEqualTo("DONE");
        assertThat(done.<Map<String, Integer>>json("$.progress")).isEqualTo(Map.of("toImport", 1963, "done", 1963,
                "created", 1963, "updated", 0, "unchanged", 0, "failed", 0, "skipped", 37));
        assertThat(count("products")).isEqualTo(501);
        assertThat(count("product_variants")).isEqualTo(1963);
        assertThat(count("categories")).isEqualTo(2);
        long movements = count("stock_movements");
        assertThat(movements).isEqualTo(systemDatabase.jdbc().sql(
                "select count(*) from stock_levels where business_id = ? and on_hand > 0").param(business).query(Long.class).single());

        String errors = new String(owner.download("/api/v1/imports/" + first.json("$.id") + "/errors.csv").body(),
                StandardCharsets.UTF_8);
        List<String> lines = errors.lines().toList();
        assertThat(lines).hasSize(38);
        assertThat(lines.get(0)).isEqualTo("﻿Row,Problems,Name,Price,Stock,Size,Category");
        assertThat(lines.get(1)).isEqualTo("55,\"B Price is not a price, like 1200 or ৳1,200.50\",Item 13,ask,5,L,Bulk > Items");

        ApiResponse again = commit(upload("bulk.csv", twoThousandRows()));

        assertThat(again.<Map<String, Integer>>json("$.progress")).isEqualTo(Map.of("toImport", 1963, "done", 1963,
                "created", 0, "updated", 0, "unchanged", 1963, "failed", 0, "skipped", 37));
        assertThat(count("products")).isEqualTo(501);
        assertThat(count("product_variants")).isEqualTo(1963);
        assertThat(count("stock_movements")).isEqualTo(movements);
        System.out.printf("2,000-row import: %d ms%n", millis);
    }

    @Test
    void existingProductsAreUpdatedAndGrowNewVariants() {
        ApiResponse kurti = owner.post("/api/v1/products", Map.of("name", "Cotton Kurti", "price", 120000,
                "openingStock", 3, "options", List.of(Map.of("name", "Size", "values", List.of("S", "M"))))).expectStatus(201);
        String small = kurti.json("$.variants[0].sku");
        String smallId = kurti.json("$.variants[0].id");
        String mediumId = kurti.json("$.variants[1].id");
        byte[] csv = ("Name,SKU,Price,Cost,Stock,Size\n"
                + "Cotton Kurti," + small + ",1300,800,3,S\n"
                + "cotton kurti,,1200,,7,m\n"
                + "Cotton Kurti,,1250,,4,L\n").getBytes(StandardCharsets.UTF_8);

        ApiResponse job = upload("kurti.csv", csv);
        assertThat(owner.get("/api/v1/imports/" + job.json("$.id") + "/preview").<List<String>>json("$.rows[*].action"))
                .containsExactly("UPDATE", "UPDATE", "UPDATE");
        ApiResponse done = commit(job);

        assertThat(done.<Integer>json("$.progress.updated")).isEqualTo(2);
        assertThat(done.<Integer>json("$.progress.created")).isEqualTo(1);
        ApiResponse product = owner.get("/api/v1/products/" + kurti.json("$.id"));
        assertThat(product.<List<String>>json("$.options[0].values")).containsExactly("S", "M", "L");
        assertThat(product.<List<Integer>>json("$.variants[*].price")).containsExactly(130000, 120000, 125000);
        assertThat(product.<Integer>json("$.variants[0].costPrice")).isEqualTo(80000);
        assertThat(product.<List<Integer>>json("$.variants[*].onHand")).containsExactly(3, 7, 4);
        assertThat(product.<String>json("$.variants[2].sku")).startsWith("COTTONKU-L");

        ApiResponse history = owner.get("/api/v1/variants/" + mediumId + "/movements");
        assertThat(history.<List<String>>json("$.items[*].type")).containsExactly("ADJUST", "OPENING");
        assertThat(history.<String>json("$.items[0].referenceType")).isEqualTo("IMPORT");
        assertThat(history.<String>json("$.items[0].referenceId")).isEqualTo(job.json("$.id"));
        assertThat(history.<String>json("$.items[0].by.name")).isEqualTo("Test Seller");
        // Stock that already matched the sheet was left alone.
        assertThat(owner.get("/api/v1/variants/" + smallId + "/movements").<List<String>>json("$.items[*].type"))
                .containsExactly("OPENING");
    }

    @Test
    void newCategoriesAreMadeOnceAndProductsFiledUnderThem() {
        byte[] csv = "Name,Price,Category\nKurti,100,Women > Kurti\nSaree,200,Women > Saree\nWallet,50,Accessories\n"
                .getBytes(StandardCharsets.UTF_8);

        commit(upload("a.csv", csv));
        commit(upload("a.csv", csv));

        ApiResponse tree = owner.get("/api/v1/categories");
        assertThat(tree.<List<String>>json("$[*].name")).containsExactlyInAnyOrder("Women", "Accessories");
        assertThat(tree.<List<String>>json("$[?(@.name == 'Women')].children[*].name")).containsExactly("Kurti", "Saree");
        assertThat(count("products")).isEqualTo(3);
    }

    @Test
    void aProductThatCannotBeAppliedFailsAloneWithItsReason() {
        owner.post("/api/v1/products", Map.of("name", "Panjabi", "price", 100,
                "options", List.of(Map.of("name", "Size", "values", List.of("M"))))).expectStatus(201);
        byte[] csv = "Name,Price,Color\nScarf,100,Red\nPanjabi,200,White\nShawl,300,Blue\n".getBytes(StandardCharsets.UTF_8);

        ApiResponse done = commit(upload("mixed.csv", csv));

        assertThat(done.<String>json("$.status")).isEqualTo("DONE");
        assertThat(done.<Integer>json("$.progress.created")).isEqualTo(2);
        assertThat(done.<Integer>json("$.progress.failed")).isEqualTo(1);
        String errors = new String(owner.download("/api/v1/imports/" + done.json("$.id") + "/errors.csv").body(),
                StandardCharsets.UTF_8);
        assertThat(errors.lines().toList()).containsExactly("﻿Row,Problems,Name,Price,Color",
                "3,A Product name has different options from the product in the shop (Size),Panjabi,200,White");
    }

    @Test
    void anImportIsCommittedOnceAndOnlyWithSomethingToImport() {
        ApiResponse job = upload("one.csv", "Name,Price\nKurti,100\n".getBytes(StandardCharsets.UTF_8));
        commit(job);

        owner.post("/api/v1/imports/" + job.json("$.id") + "/commit", null).expectError(409, "import.not_editable");
        owner.put("/api/v1/imports/" + job.json("$.id") + "/mapping", Map.of("columns", List.of(
                Map.of("index", 0, "field", "NAME"), Map.of("index", 1, "field", "PRICE")))).expectError(409, "import.not_editable");

        ApiResponse bad = upload("bad.csv", "Name,Price\nKurti,ask\n".getBytes(StandardCharsets.UTF_8));
        owner.post("/api/v1/imports/" + bad.json("$.id") + "/commit", null).expectError(409, "import.nothing_valid");
        assertThat(owner.get("/api/v1/imports/" + bad.json("$.id")).<String>json("$.status")).isEqualTo("MAPPING");
    }

    @Test
    void theCommitterIsToldAndTheImportIsAudited() {
        ApiResponse done = commit(upload("eid.csv", "Name,Price\nKurti,100\nShirt,ask\n".getBytes(StandardCharsets.UTF_8)));

        assertThat(owner.get("/api/v1/notifications").<String>json("$.items[0].title"))
                .isEqualTo("Import of eid.csv finished: 1 rows imported, 1 with problems.");
        ApiResponse log = owner.get("/api/v1/business/audit-log");
        assertThat(log.<String>json("$.items[0].action")).isEqualTo("import.committed");
        assertThat(log.<String>json("$.items[0].entityId")).isEqualTo(done.json("$.id"));
    }

    // The sweeper.

    @Test
    void anImportWhoseStartWasLostIsRunByTheSweeper() {
        // The sweeper runs it on this test's thread: no request, no signed-in member.
        ApiResponse job = upload("lost.csv", "Name,Price,Stock\nKurti,100,4\n".getBytes(StandardCharsets.UTF_8));
        UUID id = UUID.fromString(job.json("$.id"));
        systemDatabase.jdbc().sql("""
                        update import_jobs set status = 'QUEUED', queued_at = ?,
                               committed_by = (select created_by from import_jobs where id = ?)
                        where id = ?
                        """)
                .params(Timestamp.from(clock.instant()), id, id).update();

        assertThat(sweeper.run()).isZero();
        clock.advance(Duration.ofMinutes(2));
        assertThat(sweeper.run()).isPositive();

        assertThat(owner.get("/api/v1/imports/" + id).<String>json("$.status")).isEqualTo("DONE");
        assertThat(count("products")).isEqualTo(1);
        String variant = owner.get("/api/v1/products").json("$.items[0].id");
        String variantId = owner.get("/api/v1/products/" + variant).json("$.variants[0].id");
        ApiResponse history = owner.get("/api/v1/variants/" + variantId + "/movements");
        assertThat(history.<String>json("$.items[0].type")).isEqualTo("OPENING");
        assertThat(history.<String>json("$.items[0].by.name")).as("the member who committed").isEqualTo("Test Seller");
    }

    @Test
    void aStalledImportResumesWhereItStopped() {
        ApiResponse done = commit(upload("stall.csv", "Name,Price\nA,1\nB,2\nC,3\n".getBytes(StandardCharsets.UTF_8)));
        UUID id = UUID.fromString(done.json("$.id"));
        // As if the app stopped after the first chunk: row 2 done, rows 3 and 4 not.
        systemDatabase.jdbc().sql("update import_rows set result = null where job_id = ? and row_number > 2").param(id).update();
        systemDatabase.jdbc().sql("update import_jobs set status = 'RUNNING', heartbeat_at = ?, finished_at = null where id = ?")
                .params(Timestamp.from(clock.instant()), id).update();

        assertThat(sweeper.run()).as("a live heartbeat is left alone").isZero();
        clock.advance(Duration.ofMinutes(6));
        assertThat(sweeper.run()).isPositive();

        ApiResponse resumed = owner.get("/api/v1/imports/" + id);
        assertThat(resumed.<String>json("$.status")).isEqualTo("DONE");
        // Row 2 kept its result; rows 3 and 4 were applied again and found nothing to change.
        assertThat(resumed.<Map<String, Integer>>json("$.progress")).containsEntry("created", 1).containsEntry("unchanged", 2);
        assertThat(count("products")).isEqualTo(3);
    }

    private ApiResponse upload(String name, byte[] content) {
        return owner.upload("/api/v1/imports", name, content).expectStatus(201);
    }

    /** Commits, then reads the job back (it has finished: tests run imports inline). */
    private ApiResponse commit(ApiResponse job) {
        owner.post("/api/v1/imports/" + job.json("$.id") + "/commit", null).expectStatus(202);
        return owner.get("/api/v1/imports/" + job.json("$.id")).expectStatus(200);
    }

    private long count(String table) {
        return systemDatabase.jdbc().sql("select count(*) from " + table + " where business_id = ?")
                .param(business).query(Long.class).single();
    }
}
