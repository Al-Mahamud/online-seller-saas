package com.mojud.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import com.mojud.shared.tenant.TenantScope;
import com.mojud.support.ApiClient;
import com.mojud.support.ApiResponse;
import com.mojud.support.CapturedMessages;
import com.mojud.support.IntegrationTest;
import com.mojud.support.TestAccounts;
import com.mojud.support.TestBusinesses;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

/** Delivery zones and charge quotes (implementation-plan.md 6b). */
@IntegrationTest
class DeliveryZoneTests {

    static final int DHAKA = 47;
    static final int GAZIPUR = 41;
    static final int NARAYANGANJ = 43;
    static final int SYLHET = 36;
    static final int MIRPUR = 9027;
    static final int SAVAR = 365;
    static final int KERANIGANJ = 367;

    @LocalServerPort
    int port;

    @Autowired
    CapturedMessages messages;

    @Autowired
    GeoDirectory geo;

    @Autowired
    DeliveryCharges charges;

    ApiClient owner;
    UUID business;

    @BeforeEach
    void business() {
        owner = new ApiClient(port);
        TestAccounts.signUp(owner, messages);
        business = TestBusinesses.create(owner, "Delivery Shop");
    }

    @Test
    void aNewBusinessChargesInsideAndOutsideDhaka() {
        ApiResponse zones = owner.get("/api/v1/delivery-zones").expectStatus(200);

        assertThat(zones.<List<String>>json("$[*].name")).containsExactly("Inside Dhaka", "Outside Dhaka");
        assertThat(zones.<List<Integer>>json("$[*].charge")).containsExactly(7000, 13000);
        assertThat(zones.<List<Boolean>>json("$[*].isDefault")).containsExactly(false, true);
        assertThat(zones.<List<String>>json("$[0].districts[*].nameEn")).containsExactly("Dhaka");

        assertThat(quote("areaId=" + MIRPUR)).isEqualTo(List.of("Inside Dhaka", 7000, "DISTRICT"));
        assertThat(quote("areaId=" + SAVAR)).isEqualTo(List.of("Inside Dhaka", 7000, "DISTRICT"));
        assertThat(quote("districtId=" + SYLHET)).isEqualTo(List.of("Outside Dhaka", 13000, "DEFAULT"));
        assertThat(quote("")).isEqualTo(List.of("Outside Dhaka", 13000, "DEFAULT"));
    }

    @Test
    void everyDistrictAndEveryAreaMatchesExactlyOneZone() {
        owner.put("/api/v1/delivery-zones", Map.of("zones", List.of(
                zone("Dhaka city", 7000, List.of(DHAKA), List.of()),
                zone("Dhaka sub-areas", 10000, List.of(GAZIPUR, NARAYANGANJ), List.of(SAVAR, KERANIGANJ)),
                Map.of("name", "Everywhere else", "charge", 13000, "isDefault", true)))).expectStatus(200);

        for (GeoDirectory.District district : geo.districts()) {
            String expected = district.id() == DHAKA ? "Dhaka city"
                    : district.id() == GAZIPUR || district.id() == NARAYANGANJ ? "Dhaka sub-areas" : "Everywhere else";
            assertThat(quoteInBusiness(null, district.id()).zoneName()).as(district.nameEn()).isEqualTo(expected);
            for (GeoDirectory.Area area : geo.areas(district.id())) {
                String expectedForArea = area.id() == SAVAR || area.id() == KERANIGANJ ? "Dhaka sub-areas" : expected;
                assertThat(quoteInBusiness(area.id(), null).zoneName()).as(area.nameEn()).isEqualTo(expectedForArea);
            }
        }
        assertThat(quote("areaId=" + SAVAR)).isEqualTo(List.of("Dhaka sub-areas", 10000, "AREA"));
        assertThat(quote("areaId=" + MIRPUR)).isEqualTo(List.of("Dhaka city", 7000, "DISTRICT"));
    }

    @Test
    void quotesCheckTheirPlaces() {
        owner.get("/api/v1/delivery-zones/quote?areaId=99999").expectError(400, "validation.failed");
        owner.get("/api/v1/delivery-zones/quote?districtId=99").expectError(400, "validation.failed");
        owner.get("/api/v1/delivery-zones/quote?areaId=" + MIRPUR + "&districtId=" + SYLHET).expectError(400, "validation.failed");
    }

    @Test
    void zonesAreReplacedAsAWholeAndKeepTheirIds() {
        ApiResponse before = owner.get("/api/v1/delivery-zones");
        String inside = before.json("$[0].id");
        String outside = before.json("$[1].id");

        // Rename both by swapping their names, change a charge, add a zone.
        ApiResponse after = owner.put("/api/v1/delivery-zones", Map.of("zones", List.of(
                Map.of("id", inside, "name", "Outside Dhaka", "charge", 15000, "isDefault", true),
                Map.of("id", outside, "name", "Inside Dhaka", "charge", 6000, "districtIds", List.of(DHAKA)),
                zone("Sylhet", 11000, List.of(SYLHET), List.of())))).expectStatus(200);

        assertThat(after.<List<String>>json("$[*].id")).startsWith(inside, outside);
        assertThat(after.<List<String>>json("$[*].name")).containsExactly("Outside Dhaka", "Inside Dhaka", "Sylhet");
        assertThat(quote("districtId=" + SYLHET)).isEqualTo(List.of("Sylhet", 11000, "DISTRICT"));

        ApiResponse fewer = owner.put("/api/v1/delivery-zones", Map.of("zones", List.of(
                Map.of("id", inside, "name", "Everywhere", "charge", 12000, "isDefault", true)))).expectStatus(200);
        assertThat(fewer.<List<String>>json("$[*].name")).containsExactly("Everywhere");
        assertThat(quote("areaId=" + MIRPUR)).isEqualTo(List.of("Everywhere", 12000, "DEFAULT"));
        assertThat(owner.get("/api/v1/business/audit-log").<String>json("$.items[0].action")).isEqualTo("delivery_zones.changed");
    }

    @Test
    void impossibleSetsAreRefused() {
        Map<String, Object> fallback = Map.of("name", "Rest", "charge", 13000, "isDefault", true);

        put(List.of()).expectError(400, "validation.failed");
        put(List.of(zone("No default", 100, List.of(), List.of()))).expectError(400, "validation.failed");
        put(List.of(fallback, Map.of("name", "Second default", "charge", 1, "isDefault", true))).expectError(400, "validation.failed");
        put(List.of(fallback, zone("rest", 100, List.of(), List.of()))).expectError(400, "validation.failed");
        put(List.of(fallback, zone("A", 100, List.of(DHAKA), List.of()), zone("B", 100, List.of(DHAKA), List.of())))
                .expectError(400, "validation.failed");
        put(List.of(fallback, zone("A", 100, List.of(), List.of(SAVAR)), zone("B", 100, List.of(), List.of(SAVAR))))
                .expectError(400, "validation.failed");
        put(List.of(fallback, zone("A", 100, List.of(99), List.of()))).expectError(400, "validation.failed");
        put(List.of(fallback, zone("A", -1, List.of(), List.of()))).expectError(400, "validation.failed");
        put(List.of(fallback, zone("A", 10_000_001, List.of(), List.of()))).expectError(400, "validation.failed");
        put(List.of(Map.of("id", UUID.randomUUID().toString(), "name", "Made up", "charge", 1, "isDefault", true)))
                .expectError(400, "validation.failed");

        assertThat(owner.get("/api/v1/delivery-zones").<List<String>>json("$[*].name")).containsExactly("Inside Dhaka", "Outside Dhaka");
    }

    @Test
    void staffSeeZonesAndChargesButDoNotChangeThem() {
        ApiClient staff = new ApiClient(port);
        TestAccounts.Account account = TestAccounts.signUp(staff, messages);
        owner.post("/api/v1/staff/invites", Map.of("to", account.email(), "role", "STAFF")).expectStatus(201);
        String body = messages.to(account.email()).get(messages.to(account.email()).size() - 1).body();
        staff.post("/api/v1/invites/accept", Map.of("token", body.substring(body.indexOf("/invites#") + 9).split("\\s")[0]))
                .expectStatus(200);

        staff.get("/api/v1/delivery-zones").expectStatus(200);
        staff.get("/api/v1/delivery-zones/quote?areaId=" + MIRPUR).expectStatus(200);
        staff.put("/api/v1/delivery-zones", Map.of("zones", List.of(Map.of("name", "X", "charge", 1, "isDefault", true))))
                .expectStatus(403);
    }

    private ApiResponse put(List<Map<String, Object>> zones) {
        return owner.put("/api/v1/delivery-zones", Map.of("zones", zones));
    }

    private static Map<String, Object> zone(String name, long charge, List<Integer> districts, List<Integer> areas) {
        return Map.of("name", name, "charge", charge, "districtIds", districts, "areaIds", areas);
    }

    /** [zone name, charge, matched by] */
    private List<Object> quote(String query) {
        ApiResponse quote = owner.get("/api/v1/delivery-zones/quote" + (query.isEmpty() ? "" : "?" + query)).expectStatus(200);
        return List.of(quote.json("$.zoneName"), quote.json("$.charge"), quote.json("$.matchedBy"));
    }

    private DeliveryCharges.Quote quoteInBusiness(Integer areaId, Integer districtId) {
        return TenantScope.call(business, () -> charges.quote(areaId, districtId));
    }
}
