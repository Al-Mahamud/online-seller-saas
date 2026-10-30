package com.mojud.importing;

import static org.assertj.core.api.Assertions.assertThat;

import com.mojud.support.ApiClient;
import com.mojud.support.ApiResponse;
import com.mojud.support.CapturedMessages;
import com.mojud.support.IntegrationTest;
import com.mojud.support.TestAccounts;
import com.mojud.support.TestBusinesses;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

/** Upload, column mapping and preview (implementation-plan.md 5a). */
@IntegrationTest
class ImportTests {

    @LocalServerPort
    int port;

    @Autowired
    CapturedMessages messages;

    ApiClient owner;

    @BeforeEach
    void business() {
        owner = new ApiClient(port);
        TestAccounts.signUp(owner, messages);
        TestBusinesses.create(owner, "Import Shop");
    }

    /**
     * A sheet as sellers send them: a merged title, a blank row, Bangla and English headers with
     * units, "৳1,200" and "1,250/-" prices, Bangla digits, numbers stored as numbers, and mistakes.
     */
    static byte[] messySheet() throws IOException {
        return SheetReaderTest.xlsx(sheet -> {
            sheet.value(0, 0, "Rahima's Kurti House — Eid stock 2026");
            sheet.range(0, 0, 0, 6).merge();
            row(sheet, 2, "পণ্যের নাম", "SKU", "দাম (৳)", "Stock", "Category", "Size", "Colour");
            row(sheet, 3, "Cotton Kurti", "KURTI-S", "৳1,200", "১০", "Women > Kurti", "S", "Black");
            row(sheet, 4, "Cotton Kurti", "KURTI-M", "1,250/-", 5, "Women > Kurti", "M", "Black");
            row(sheet, 6, "Leather Wallet", "WAL-1", 950, 3, "Accessories");
            row(sheet, 7, "", "X-1", 100);
            row(sheet, 8, "Bad Price", "BP-1", "call for price");
            row(sheet, 9, "Cotton Kurti", "kurti-s", 1200, 1, "Women > Kurti", "L", "Black");
            row(sheet, 10, "Cotton Kurti", "KURTI-S2", 1200, 2, "Women > Kurti", "S", "Black");
            row(sheet, 11, "Cotton Kurti", "KURTI-XL", 1300, 2, "Women > Kurti", "XL");
            row(sheet, 12, "Gift Box", "GB-1", "৫০০.৫০", "2.5");
            row(sheet, 13, "Panjabi", "PJ-1", 2200, 4, "Men > Eid > Panjabi");
        });
    }

    @Test
    void aMessySheetIsReadMappedAndChecked() throws IOException {
        ApiResponse job = owner.upload("/api/v1/imports", "eid stock.xlsx", messySheet()).expectStatus(201);

        assertThat(job.<String>json("$.fileName")).isEqualTo("eid stock.xlsx");
        assertThat(job.<String>json("$.fileType")).isEqualTo("XLSX");
        assertThat(job.<String>json("$.status")).isEqualTo("MAPPING");
        assertThat(job.<Integer>json("$.headerRow")).isEqualTo(3);
        assertThat(job.<List<String>>json("$.columns[*].field"))
                .containsExactly("NAME", "SKU", "PRICE", "STOCK", "CATEGORY", "OPTION", "OPTION");
        assertThat(job.<List<String>>json("$.columns[5,6].optionName")).containsExactly("Size", "Colour");
        assertThat(job.<List<String>>json("$.columns[2].samples")).containsExactly("৳1,200", "1,250/-", "950");
        assertThat(job.<Map<String, Integer>>json("$.totals"))
                .isEqualTo(Map.of("rows", 10, "valid", 3, "invalid", 7, "create", 3, "update", 0));
        assertThat(job.<List<String>>json("$.newCategories")).containsExactly("Accessories", "Women > Kurti");

        ApiResponse preview = owner.get("/api/v1/imports/" + job.json("$.id") + "/preview").expectStatus(200);

        assertThat(preview.<List<Integer>>json("$.rows[*].row")).containsExactly(4, 5, 7, 8, 9, 10, 11, 12, 13, 14);
        assertThat(preview.<List<Integer>>json("$.rows[0,1,2].price")).containsExactly(120000, 125000, 95000);
        assertThat(preview.<Integer>json("$.rows[0].stock")).isEqualTo(10);
        assertThat(preview.<Map<String, String>>json("$.rows[0].options")).isEqualTo(Map.of("Size", "S", "Colour", "Black"));
        assertThat(preview.<String>json("$.rows[0].action")).isEqualTo("CREATE");
        assertThat(errors(preview, 3)).containsExactly("A Product name is required");
        assertThat(errors(preview, 4)).containsExactly("C Price is not a price, like 1200 or ৳1,200.50");
        assertThat(errors(preview, 5)).containsExactly("B SKU is also used on row 4");
        assertThat(errors(preview, 6)).containsExactly("A Product name repeats the variant on row 4");
        assertThat(errors(preview, 7)).containsExactly("A Product name has different options from row 4, the same product");
        assertThat(errors(preview, 8)).containsExactly("D Stock must be a whole number, 0 or more");
        assertThat(preview.<Integer>json("$.rows[8].price")).isEqualTo(50050);
        assertThat(errors(preview, 9)).containsExactly("E Category can be at most two levels, like \"Women > Kurti\"");
        assertThat(preview.<Object>json("$.rows[3].action")).isNull();
    }

    @Test
    void errorsAreInTheReadersLanguageAndCanBeListedAlone() throws IOException {
        String id = owner.upload("/api/v1/imports", "eid.xlsx", messySheet()).expectStatus(201).json("$.id");
        owner.patch("/api/v1/me", Map.of("locale", "bn")).expectStatus(200);

        ApiResponse invalid = owner.get("/api/v1/imports/" + id + "/preview?invalidOnly=true").expectStatus(200);

        assertThat(invalid.<List<Integer>>json("$.rows[*].row")).containsExactly(8, 9, 10, 11, 12, 13, 14);
        assertThat(errors(invalid, 0)).containsExactly("A পণ্যের নাম দিতে হবে");
        assertThat(errors(invalid, 2)).containsExactly("B SKU ৪ নম্বর সারিতেও আছে");
    }

    @Test
    void changingTheMappingRechecksEveryRow() throws IOException {
        String id = owner.upload("/api/v1/imports", "eid.xlsx", messySheet()).expectStatus(201).json("$.id");

        // Without the option columns, the Cotton Kurti rows are one variant given five times.
        ApiResponse job = owner.put("/api/v1/imports/" + id + "/mapping", Map.of("columns", List.of(
                Map.of("index", 0, "field", "NAME"), Map.of("index", 1, "field", "SKU"),
                Map.of("index", 2, "field", "PRICE")))).expectStatus(200);

        assertThat(job.<List<String>>json("$.columns[*].field")).containsExactly("NAME", "SKU", "PRICE", null, null, null, null);
        assertThat(job.<Integer>json("$.totals.valid")).isEqualTo(4);
        assertThat(job.<List<String>>json("$.newCategories")).isEmpty();
        ApiResponse preview = owner.get("/api/v1/imports/" + id + "/preview");
        assertThat(errors(preview, 1)).containsExactly("A Product name repeats the variant on row 4");

        // An option column may be renamed.
        job = owner.put("/api/v1/imports/" + id + "/mapping", Map.of("columns", List.of(
                Map.of("index", 0, "field", "NAME"), Map.of("index", 2, "field", "PRICE"),
                Map.of("index", 5, "field", "OPTION", "optionName", "Saiz"), Map.of("index", 6, "field", "OPTION"))))
                .expectStatus(200);
        assertThat(job.<List<String>>json("$.columns[5,6].optionName")).containsExactly("Saiz", "Colour");
        assertThat(owner.get("/api/v1/imports/" + id + "/preview").<Map<String, String>>json("$.rows[0].options"))
                .isEqualTo(Map.of("Saiz", "S", "Colour", "Black"));
    }

    @Test
    void impossibleMappingsAreRefused() throws IOException {
        String path = "/api/v1/imports/" + owner.upload("/api/v1/imports", "eid.xlsx", messySheet()).json("$.id") + "/mapping";
        Map<String, Object> name = Map.of("index", 0, "field", "NAME");
        Map<String, Object> price = Map.of("index", 2, "field", "PRICE");

        owner.put(path, Map.of("columns", List.of(name, price, Map.of("index", 0, "field", "SKU"))))
                .expectError(400, "validation.failed");
        owner.put(path, Map.of("columns", List.of(name, price, Map.of("index", 1, "field", "NAME"))))
                .expectError(400, "validation.failed");
        owner.put(path, Map.of("columns", List.of(name, price, Map.of("index", 7, "field", "SKU"))))
                .expectError(400, "validation.failed");
        owner.put(path, Map.of("columns", List.of(name, price, option(3, "A"), option(4, "B"), option(5, "C"), option(6, "D"))))
                .expectError(400, "validation.failed");
        owner.put(path, Map.of("columns", List.of(name, price, option(5, "Size"), option(6, "size"))))
                .expectError(400, "validation.failed");
        owner.put(path, Map.of("headerRow", 99, "columns", List.of(name, price))).expectError(400, "validation.failed");
        owner.put(path, Map.of("columns", List.of(name, Map.of("index", 2, "field", "BARCODE")))).expectStatus(400);
    }

    @Test
    void existingSkusAreUpdatesAndExistingCategoriesAreFound() {
        String women = owner.post("/api/v1/categories", Map.of("name", "Women")).expectStatus(201).json("$.id");
        owner.post("/api/v1/categories", Map.of("name", "Kurti", "parentId", women)).expectStatus(201);
        owner.post("/api/v1/products", Map.of("name", "Cotton Kurti", "price", 120000, "sku", "KURTI-S")).expectStatus(201);
        byte[] csv = "Name,SKU,Price,Category\nCotton Kurti,kurti-s,1300,Women > Kurti\nShirt,SH-1,500,kurti\nScarf,SC-1,300,Women > Scarves\n"
                .getBytes(StandardCharsets.UTF_8);

        ApiResponse job = owner.upload("/api/v1/imports", "update.csv", csv).expectStatus(201);

        assertThat(job.<Map<String, Integer>>json("$.totals"))
                .isEqualTo(Map.of("rows", 3, "valid", 3, "invalid", 0, "create", 2, "update", 1));
        assertThat(job.<List<String>>json("$.newCategories")).containsExactly("Women > Scarves");
        assertThat(owner.get("/api/v1/imports/" + job.json("$.id") + "/preview").<List<String>>json("$.rows[*].action"))
                .containsExactly("UPDATE", "CREATE", "CREATE");

        // A second "Kurti" makes the bare name ambiguous.
        String girls = owner.post("/api/v1/categories", Map.of("name", "Girls")).expectStatus(201).json("$.id");
        owner.post("/api/v1/categories", Map.of("name", "Kurti", "parentId", girls)).expectStatus(201);
        ApiResponse again = owner.upload("/api/v1/imports", "update.csv", csv).expectStatus(201);
        assertThat(errors(owner.get("/api/v1/imports/" + again.json("$.id") + "/preview"), 1))
                .containsExactly("D Category matches more than one category; write it as \"Parent > Category\"");
    }

    @Test
    void theTemplateReadsBackInBothLanguages() {
        for (String language : List.of("en", "bn")) {
            owner.patch("/api/v1/me", Map.of("locale", language)).expectStatus(200);
            HttpResponse<byte[]> template = owner.download("/api/v1/imports/template.xlsx");
            assertThat(template.statusCode()).isEqualTo(200);
            assertThat(template.headers().firstValue("Content-Disposition")).hasValueSatisfying(
                    value -> assertThat(value).contains("mojud-products.xlsx"));

            ApiResponse job = owner.upload("/api/v1/imports", "template.xlsx", template.body()).expectStatus(201);

            assertThat(job.<List<String>>json("$.columns[*].field")).as(language).containsExactly("NAME", "SKU", "PRICE",
                    "COST_PRICE", "STOCK", "CATEGORY", "OPTION", "OPTION", "DESCRIPTION", "LOW_STOCK_THRESHOLD");
            assertThat(job.<Integer>json("$.totals.valid")).as(language).isEqualTo(3);
            assertThat(job.<String>json("$.columns[0].header")).isEqualTo(language.equals("bn") ? "পণ্যের নাম" : "Product name");
        }
    }

    @Test
    void aFullSizeSheetIsCheckedInGoodTime() {
        StringBuilder csv = new StringBuilder("Name,SKU,Price,Stock,Size\n");
        for (int i = 1; i < SheetReader.MAX_ROWS; i++) {
            csv.append("Product ").append(i / 4).append(",SKU-").append(i).append(",").append(100 + i % 50).append(",")
                    .append(i % 7).append(",").append(new String[] {"S", "M", "L", "XL"}[i % 4]).append("\n");
        }
        long start = System.nanoTime();
        ApiResponse job = owner.upload("/api/v1/imports", "big.csv", csv.toString().getBytes(StandardCharsets.UTF_8))
                .expectStatus(201);
        long uploadMillis = (System.nanoTime() - start) / 1_000_000;
        start = System.nanoTime();
        owner.put("/api/v1/imports/" + job.json("$.id") + "/mapping", Map.of("columns", List.of(
                Map.of("index", 0, "field", "NAME"), Map.of("index", 2, "field", "PRICE"),
                Map.of("index", 4, "field", "OPTION")))).expectStatus(200);
        long remapMillis = (System.nanoTime() - start) / 1_000_000;

        assertThat(job.<Integer>json("$.totals.rows")).isEqualTo(SheetReader.MAX_ROWS - 1);
        assertThat(job.<Integer>json("$.totals.valid")).isEqualTo(SheetReader.MAX_ROWS - 1);
        System.out.printf("10,000-row import: upload %d ms, remap %d ms%n", uploadMillis, remapMillis);
        assertThat(uploadMillis + remapMillis).isLessThan(30_000);
    }

    @Test
    void aSheetWithHeadersWeDontRecogniseCanBeMappedOneColumnAtATime() {
        var upload = owner.upload("/api/v1/imports", "own.csv", "Item Title,Taka\nShirt,500\n".getBytes(StandardCharsets.UTF_8))
                .expectStatus(201);
        String id = upload.json("$.id");
        assertThat(upload.<List<String>>json("$.missingColumns")).containsExactly("NAME", "PRICE");
        assertThat(upload.<Integer>json("$.totals.valid")).isZero();
        assertThat(upload.<Integer>json("$.totals.rows")).isEqualTo(1);
        owner.post("/api/v1/imports/" + id + "/commit", Map.of()).expectError(400, "validation.failed");

        // Without the price it still can't start: a new product must have one.
        var nameOnly = owner.put("/api/v1/imports/" + id + "/mapping", Map.of("columns", List.of(Map.of("index", 0, "field", "NAME"))))
                .expectStatus(200);
        assertThat(nameOnly.<List<String>>json("$.missingColumns")).containsExactly("PRICE");
        assertThat(nameOnly.<Integer>json("$.totals.valid")).isZero();
        owner.post("/api/v1/imports/" + id + "/commit", Map.of()).expectError(400, "validation.failed");

        var both = owner.put("/api/v1/imports/" + id + "/mapping", Map.of("columns",
                List.of(Map.of("index", 0, "field", "NAME"), Map.of("index", 1, "field", "PRICE")))).expectStatus(200);
        assertThat(both.<List<String>>json("$.missingColumns")).isEmpty();
        assertThat(both.<Integer>json("$.totals.valid")).isEqualTo(1);
        owner.post("/api/v1/imports/" + id + "/commit", Map.of()).expectStatus(202);
    }

    @Test
    void aFileWithNoRowsBelowItsHeaderIsEmpty() {
        owner.upload("/api/v1/imports", "text.xlsx", "this is not a spreadsheet".getBytes(StandardCharsets.UTF_8))
                .expectError(400, "import.empty");
        owner.upload("/api/v1/imports", "headers.csv", "Name,Price\n".getBytes(StandardCharsets.UTF_8))
                .expectError(400, "import.empty");
    }

    @Test
    void filesThatAreNotSheetsAreRefused() {
        owner.upload("/api/v1/imports", "old.xls", new byte[] {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0,
                (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1, 0, 0}).expectError(400, "import.unreadable");
        owner.upload("/api/v1/imports", "empty.csv", "\n\n".getBytes(StandardCharsets.UTF_8)).expectError(400, "import.empty");
        owner.upload("/api/v1/imports", "big.csv", new byte[10 * 1024 * 1024 + 1]).expectError(413, "file.too_large");
    }

    @Test
    void theFileNameKeepsNoFolders() {
        ApiResponse job = owner.upload("/api/v1/imports", "C:\\\\Users\\\\rahima\\\\stock.csv",
                "Name,Price\nKurti,1\n".getBytes(StandardCharsets.UTF_8)).expectStatus(201);

        assertThat(job.<String>json("$.fileName")).isEqualTo("stock.csv");
    }

    @Test
    void staffCannotImport() {
        ApiClient staff = new ApiClient(port);
        TestAccounts.Account account = TestAccounts.signUp(staff, messages);
        owner.post("/api/v1/staff/invites", Map.of("to", account.email(), "role", "STAFF")).expectStatus(201);
        String body = messages.to(account.email()).get(messages.to(account.email()).size() - 1).body();
        staff.post("/api/v1/invites/accept", Map.of("token", body.substring(body.indexOf("/invites#") + 9).split("\\s")[0]))
                .expectStatus(200);

        staff.upload("/api/v1/imports", "s.csv", "Name,Price\nKurti,1\n".getBytes(StandardCharsets.UTF_8)).expectStatus(403);
    }

    /** "<column letter> <field> <message>" for the {@code index}-th row of the preview (0 is the first). */
    private static List<String> errors(ApiResponse preview, int index) {
        List<Map<String, String>> errors = preview.json("$.rows[" + index + "].errors");
        return errors.stream().map(e -> e.get("column") + " " + e.get("field") + " " + e.get("message")).toList();
    }

    private static Map<String, Object> option(int index, String name) {
        return Map.of("index", index, "field", "OPTION", "optionName", name);
    }

    private static void row(org.dhatim.fastexcel.Worksheet sheet, int row, Object... values) {
        for (int column = 0; column < values.length; column++) {
            if (values[column] instanceof Number number) {
                sheet.value(row, column, number);
            } else if (!values[column].toString().isEmpty()) {
                sheet.value(row, column, values[column].toString());
            }
        }
    }
}
