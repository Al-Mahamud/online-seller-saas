package com.mojud.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import com.mojud.support.ApiClient;
import com.mojud.support.ApiResponse;
import com.mojud.support.CapturedMessages;
import com.mojud.support.IntegrationTest;
import com.mojud.support.TestAccounts;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

/** Bangladesh's divisions, districts and areas (implementation-plan.md 6a). */
@IntegrationTest
class GeoTests {

    @LocalServerPort
    int port;

    @Autowired
    CapturedMessages messages;

    @Autowired
    GeoDirectory geo;

    @Test
    void theWholeCountryIsThereInBothLanguages() {
        assertThat(geo.divisions()).hasSize(8);
        assertThat(geo.districts()).hasSize(64);
        assertThat(geo.districts()).allSatisfy(district -> {
            assertThat(district.nameBn()).isNotBlank();
            assertThat(geo.areas(district.id())).as(district.nameEn()).isNotEmpty();
        });
        assertThat(geo.districts().stream().mapToInt(d -> geo.areas(d.id()).size()).sum()).isEqualTo(494 + 50);
        assertThat(geo.district(9)).hasValueSatisfying(d -> assertThat(d.nameEn()).isEqualTo("Cox's Bazar"));
    }

    @Test
    void dhakaHasItsCityAreasAndItsUpazilas() {
        ApiClient user = new ApiClient(port);
        TestAccounts.signUp(user, messages);

        ApiResponse dhaka = user.get("/api/v1/geo/districts/47/areas").expectStatus(200);

        assertThat(dhaka.<List<String>>json("$[*].nameEn")).contains("Mirpur", "Dhanmondi", "Uttara West", "Savar", "Keraniganj");
        assertThat(dhaka.<List<String>>json("$[?(@.nameEn == 'Mirpur')].nameBn")).containsExactly("মিরপুর");
        assertThat(dhaka.<List<String>>json("$[?(@.nameEn == 'Mirpur')].kind")).containsExactly("METRO_THANA");
        assertThat(dhaka.<List<String>>json("$[?(@.nameEn == 'Savar')].kind")).containsExactly("UPAZILA");
        assertThat(dhaka.headers().firstValue("Cache-Control")).hasValueSatisfying(v -> assertThat(v).contains("max-age=86400"));
        assertThat(user.get("/api/v1/geo/districts?divisionId=6").<List<String>>json("$[*].nameEn")).contains("Dhaka", "Gazipur")
                .doesNotContain("Sylhet");
        user.get("/api/v1/geo/districts/999/areas").expectError(404, "resource.not_found");
    }

    @Test
    void signInIsNeeded() {
        new ApiClient(port).get("/api/v1/geo/districts").expectStatus(401);
    }
}
