package com.mojud.delivery;

import com.mojud.tenancy.BusinessRole;
import com.mojud.tenancy.RequiresRole;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Delivery zones: every member sees them and the charges (orders need them); managers and owners change them. */
@RestController
@RequiresRole(BusinessRole.STAFF)
class DeliveryZoneController {

    private final DeliveryZones zones;
    private final DeliveryCharges charges;

    DeliveryZoneController(DeliveryZones zones, DeliveryCharges charges) {
        this.zones = zones;
        this.charges = charges;
    }

    @GetMapping("/api/v1/delivery-zones")
    List<DeliveryZones.Zone> list() {
        return zones.list();
    }

    record ZoneRequest(UUID id, @NotNull @Size(max = 200) String name, @NotNull @PositiveOrZero Long charge,
            Boolean isDefault, @Size(max = 64) List<Integer> districtIds, @Size(max = 600) List<Integer> areaIds) {
    }

    record ZonesRequest(@NotNull @Size(max = 50) List<@Valid @NotNull ZoneRequest> zones) {
    }

    /** The whole set: zones left out are removed; one zone is the default. */
    @PutMapping("/api/v1/delivery-zones")
    @RequiresRole(BusinessRole.MANAGER)
    List<DeliveryZones.Zone> replace(@Valid @RequestBody ZonesRequest body) {
        return zones.replace(body.zones().stream().map(zone -> new DeliveryZones.ZoneInput(zone.id(), zone.name(),
                zone.charge(), Boolean.TRUE.equals(zone.isDefault()), zone.districtIds(), zone.areaIds())).toList());
    }

    /** For the order form: the charge for an area (or a district alone, or neither: the default). */
    @GetMapping("/api/v1/delivery-zones/quote")
    DeliveryCharges.Quote quote(@RequestParam(required = false) Integer areaId,
            @RequestParam(required = false) Integer districtId) {
        return charges.quote(areaId, districtId);
    }
}
