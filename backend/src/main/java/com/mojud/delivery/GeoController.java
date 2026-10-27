package com.mojud.delivery;

import com.mojud.shared.error.ApiException;
import java.time.Duration;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Bangladesh's divisions, districts and areas, for address forms. The same for everyone, so any
 * signed-in user may read them, and browsers may keep them for a day.
 */
@RestController
class GeoController {

    private static final CacheControl ONE_DAY = CacheControl.maxAge(Duration.ofDays(1)).cachePrivate();

    private final GeoDirectory geo;

    GeoController(GeoDirectory geo) {
        this.geo = geo;
    }

    @GetMapping("/api/v1/geo/divisions")
    ResponseEntity<List<GeoDirectory.Division>> divisions() {
        return ResponseEntity.ok().cacheControl(ONE_DAY).body(geo.divisions());
    }

    /** All 64, or one division's. */
    @GetMapping("/api/v1/geo/districts")
    ResponseEntity<List<GeoDirectory.District>> districts(@RequestParam(required = false) Integer divisionId) {
        List<GeoDirectory.District> districts = divisionId == null ? geo.districts()
                : geo.districts().stream().filter(d -> d.divisionId() == divisionId).toList();
        return ResponseEntity.ok().cacheControl(ONE_DAY).body(districts);
    }

    @GetMapping("/api/v1/geo/districts/{id}/areas")
    ResponseEntity<List<GeoDirectory.Area>> areas(@PathVariable int id) {
        if (geo.district(id).isEmpty()) {
            throw ApiException.notFound();
        }
        return ResponseEntity.ok().cacheControl(ONE_DAY).body(geo.areas(id));
    }
}
