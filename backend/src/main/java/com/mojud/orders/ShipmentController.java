package com.mojud.orders;

import com.mojud.tenancy.BusinessRole;
import com.mojud.tenancy.RequiresRole;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Shipments and their COD money. Members see them; managers and owners settle with couriers. */
@RestController
@RequiresRole(BusinessRole.STAFF)
class ShipmentController {

    private final CodSettlements cod;

    ShipmentController(CodSettlements cod) {
        this.cod = cod;
    }

    /** {@code status}: IN_TRANSIT, DELIVERED, RETURNED; {@code codStatus}: NONE, WITH_COURIER, SETTLED; {@code courier}: its name. */
    @GetMapping("/api/v1/shipments")
    List<CodSettlements.Row> list(@RequestParam(required = false) String status,
            @RequestParam(required = false) String codStatus, @RequestParam(required = false) String courier) {
        return cod.list(status, codStatus, courier);
    }

    record SettleRequest(@NotNull @Size(max = 1000) List<@NotNull UUID> shipmentIds) {
    }

    /** One courier's payout: these delivered shipments' COD is now with the business. */
    @PostMapping("/api/v1/shipments/settle-cod")
    @RequiresRole(BusinessRole.MANAGER)
    CodSettlements.Settlement settle(@Valid @RequestBody SettleRequest body) {
        return cod.settle(body.shipmentIds());
    }
}
