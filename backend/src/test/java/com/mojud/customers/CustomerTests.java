package com.mojud.customers;

import static org.assertj.core.api.Assertions.assertThat;

import com.mojud.support.ApiClient;
import com.mojud.support.ApiResponse;
import com.mojud.support.CapturedMessages;
import com.mojud.support.IntegrationTest;
import com.mojud.support.TestAccounts;
import com.mojud.support.TestBusinesses;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

/** Customers (implementation-plan.md 6a). */
@IntegrationTest
class CustomerTests {

    /** Mirpur, a Dhaka city area; Savar, a Dhaka upazila; Sylhet district. */
    static final int MIRPUR = 9027;
    static final int SAVAR = 365;
    static final int DHAKA = 47;
    static final int SYLHET = 36;

    @LocalServerPort
    int port;

    @Autowired
    CapturedMessages messages;

    ApiClient owner;

    @BeforeEach
    void business() {
        owner = new ApiClient(port);
        TestAccounts.signUp(owner, messages);
        TestBusinesses.create(owner, "Customer Shop");
    }

    @Test
    void everyWayOfWritingANumberIsTheSameCustomer() {
        String id = owner.post("/api/v1/customers", Map.of("name", "Rahima Khatun", "phone", "01712-345678"))
                .expectStatus(201).json("$.id");

        for (String written : List.of("+8801712345678", "8801712345678", "০১৭১২৩৪৫৬৭৮", "+880 1712 345678")) {
            assertThat(owner.get("/api/v1/customers?phone=" + written.replace("+", "%2B").replace(" ", "%20"))
                    .<List<String>>json("$.items[*].id")).as(written).containsExactly(id);
        }
        ApiResponse taken = owner.post("/api/v1/customers", Map.of("name", "Rahima again", "phone", "8801712345678"))
                .expectError(409, "customer.phone_taken");
        assertThat(taken.<String>json("$.customerId")).isEqualTo(id);
        assertThat(owner.get("/api/v1/customers/" + id).<String>json("$.phone")).isEqualTo("+8801712345678");
        assertThat(owner.get("/api/v1/customers?phone=01900000000").<List<Object>>json("$.items")).isEmpty();
        assertThat(owner.get("/api/v1/customers?phone=not-a-number").<List<Object>>json("$.items")).isEmpty();
    }

    @Test
    void searchFindsPartsOfNamesAndNumbers() {
        String rahima = create("Rahima Khatun", "01712345678");
        String karim = create("Abdul Karim", "01819876543");
        create("Nasrin Akter", "01911223344");

        assertThat(search("rahima")).containsExactly(rahima);
        assertThat(search("Karim")).containsExactly(karim);
        assertThat(search("Rahma")).as("a small typo").contains(rahima);
        assertThat(search("987654")).containsExactly(karim);
        assertThat(search("01712")).containsExactly(rahima);
        assertThat(search("০১৮১৯")).as("Bangla digits").containsExactly(karim);
        assertThat(search("+880 1819")).containsExactly(karim);
        assertThat(search("50%")).isEmpty();
    }

    /** য় is one character (U+09DF) on some keyboards and two (য + ়) on others. */
    @Test
    void searchFindsYaTypedEitherWay() {
        String oneCharacter = create("আ\u09DFেশা", "01712345678");
        String twoCharacters = create("সা\u09AF\u09BCমা", "01819876543");

        assertThat(search("আ\u09DFে")).containsExactly(oneCharacter);
        assertThat(search("আ\u09AF\u09BCে")).containsExactly(oneCharacter);
        assertThat(search("সা\u09DF")).containsExactly(twoCharacters);
        assertThat(search("সা\u09AF\u09BC")).containsExactly(twoCharacters);
    }

    @Test
    void theListIsNewestFirstInPages() {
        String first = create("A", "01711111111");
        String second = create("B", "01711111112");
        String third = create("C", "01711111113");

        ApiResponse page = owner.get("/api/v1/customers?size=2").expectStatus(200);
        assertThat(page.<List<String>>json("$.items[*].id")).containsExactly(third, second);
        assertThat(page.<Integer>json("$.page")).isEqualTo(1);
        assertThat(page.<Integer>json("$.size")).isEqualTo(2);
        assertThat(page.<Integer>json("$.totalItems")).isEqualTo(3);
        assertThat(page.<Integer>json("$.totalPages")).isEqualTo(2);
        ApiResponse next = owner.get("/api/v1/customers?size=2&page=2");
        assertThat(next.<List<String>>json("$.items[*].id")).containsExactly(first);
        // Past the end: nothing to show, and the totals to come back with.
        ApiResponse past = owner.get("/api/v1/customers?size=2&page=9").expectStatus(200);
        assertThat(past.<List<String>>json("$.items[*].id")).isEmpty();
        assertThat(past.<Integer>json("$.totalPages")).isEqualTo(2);
    }

    @Test
    void anAreaBringsItsDistrictAndMustBeInTheOneGiven() {
        ApiResponse customer = owner.post("/api/v1/customers", Map.of("name", "Mirpur buyer", "phone", "01712000001",
                "address", "  House 4, Road 2  ", "areaId", MIRPUR)).expectStatus(201);
        assertThat(customer.<Integer>json("$.district.id")).isEqualTo(DHAKA);
        assertThat(customer.<String>json("$.district.nameBn")).isEqualTo("ঢাকা");
        assertThat(customer.<String>json("$.area.nameEn")).isEqualTo("Mirpur");
        assertThat(customer.<String>json("$.address")).isEqualTo("House 4, Road 2");

        owner.post("/api/v1/customers", Map.of("name", "X", "phone", "01712000002", "districtId", SYLHET, "areaId", MIRPUR))
                .expectError(400, "validation.failed");
        owner.post("/api/v1/customers", Map.of("name", "X", "phone", "01712000002", "areaId", 1))
                .expectStatus(201);
        owner.post("/api/v1/customers", Map.of("name", "Y", "phone", "01712000003", "areaId", 99999))
                .expectError(400, "validation.failed");
        owner.post("/api/v1/customers", Map.of("name", "Y", "phone", "01712000003", "districtId", 99))
                .expectError(400, "validation.failed");
    }

    @Test
    void changesKeepWhatIsNotGiven() {
        String id = owner.post("/api/v1/customers", Map.of("name", "Rahima", "phone", "01712000010", "altPhone", "01812000010",
                "areaId", MIRPUR, "note", "Calls after 5")).expectStatus(201).json("$.id");

        ApiResponse moved = owner.patch("/api/v1/customers/" + id, Map.of("areaId", SAVAR)).expectStatus(200);
        assertThat(moved.<String>json("$.area.nameEn")).isEqualTo("Savar");
        assertThat(moved.<String>json("$.name")).isEqualTo("Rahima");
        assertThat(moved.<String>json("$.note")).isEqualTo("Calls after 5");

        // Another district: an area outside it goes.
        ApiResponse sylhet = owner.patch("/api/v1/customers/" + id, Map.of("districtId", SYLHET)).expectStatus(200);
        assertThat(sylhet.<String>json("$.district.nameEn")).isEqualTo("Sylhet");
        assertThat(sylhet.<Object>json("$.area")).isNull();

        ApiResponse cleared = owner.patch("/api/v1/customers/" + id, Map.of("clearLocation", true, "altPhone", "",
                "note", "")).expectStatus(200);
        assertThat(cleared.<Object>json("$.district")).isNull();
        assertThat(cleared.<Object>json("$.altPhone")).isNull();
        assertThat(cleared.<String>json("$.note")).isEmpty();

        owner.patch("/api/v1/customers/" + id, Map.of("name", "Stale", "version", 0)).expectError(409, "resource.modified");
    }

    @Test
    void aNumberMovesOnlyToAFreeOne() {
        String rahima = create("Rahima", "01712000020");
        String karim = create("Karim", "01712000021");

        ApiResponse taken = owner.patch("/api/v1/customers/" + karim, Map.of("phone", "01712-000020"))
                .expectError(409, "customer.phone_taken");
        assertThat(taken.<String>json("$.customerId")).isEqualTo(rahima);
        owner.patch("/api/v1/customers/" + karim, Map.of("phone", "01712000022")).expectStatus(200);
        owner.patch("/api/v1/customers/" + karim, Map.of("phone", "12345")).expectError(400, "validation.failed");
        owner.post("/api/v1/customers", Map.of("name", " ", "phone", "01712000023")).expectError(400, "validation.failed");
    }

    @Test
    void staffWorkWithCustomers() {
        ApiClient staff = new ApiClient(port);
        TestAccounts.Account account = TestAccounts.signUp(staff, messages);
        owner.post("/api/v1/staff/invites", Map.of("to", account.email(), "role", "STAFF")).expectStatus(201);
        String body = messages.to(account.email()).get(messages.to(account.email()).size() - 1).body();
        staff.post("/api/v1/invites/accept", Map.of("token", body.substring(body.indexOf("/invites#") + 9).split("\\s")[0]))
                .expectStatus(200);

        String id = staff.post("/api/v1/customers", Map.of("name", "Walk-in", "phone", "01712000030")).expectStatus(201).json("$.id");
        staff.patch("/api/v1/customers/" + id, Map.of("note", "Prefers bKash")).expectStatus(200);
    }

    private String create(String name, String phone) {
        return owner.post("/api/v1/customers", Map.of("name", name, "phone", phone)).expectStatus(201).json("$.id");
    }

    private List<String> search(String q) {
        return owner.get("/api/v1/customers?q=" + URLEncoder.encode(q, StandardCharsets.UTF_8))
                .expectStatus(200).json("$.items[*].id");
    }
}
