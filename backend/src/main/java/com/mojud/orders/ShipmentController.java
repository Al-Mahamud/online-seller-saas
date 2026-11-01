package com.mojud.orders;

import com.mojud.shared.paging.PageQuery;
import com.mojud.shared.paging.PagedResult;
import com.mojud.tenancy.BusinessRole;
import com.mojud.tenancy.RequiresRole;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.format.annotation.DateTimeFormat;
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

    /** What couriers hold, per courier, and what of it {@code q}, {@code from} and {@code to} find. */
    @GetMapping("/api/v1/shipments/cod/summary")
    CodSettlements.Summary codSummary(@RequestParam(required = false) String q,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return cod.summary(new CodSettlements.Search(from, to, q));
    }

    /**
     * What one courier owes for, a page at a time. {@code q}: part of an order number, tracking number,
     * name or phone; {@code from} and {@code to}: the days of delivery.
     */
    @GetMapping("/api/v1/shipments/cod/parcels")
    PagedResult<CodSettlements.Parcel> codParcels(@RequestParam String courier, @RequestParam(required = false) String q,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @ParameterObject PageQuery paging) {
        return cod.parcels(courier, new CodSettlements.Search(from, to, q), paging);
    }

    /**
     * {@code expectedParcels} and {@code expectedAmount}: what the person was shown. Anything else is
     * refused, with {@code exceptIds} as the ones left out that are still owed.
     */
    record SettleMatchingRequest(@NotBlank String courier, @Nullable String q, @Nullable LocalDate from,
            @Nullable LocalDate to, @Nullable @Size(max = 10000) Set<@NotNull UUID> exceptIds,
            @PositiveOrZero long expectedParcels, @PositiveOrZero long expectedAmount) {
    }

    /** One courier's payout: all its parcels under a search, on every page, less the ones left out. */
    @PostMapping("/api/v1/shipments/settle-cod/matching")
    @RequiresRole(BusinessRole.MANAGER)
    CodSettlements.Settlement settleMatching(@Valid @RequestBody SettleMatchingRequest body) {
        return cod.settleMatching(new CodSettlements.SettleMatching(body.courier(),
                new CodSettlements.Search(body.from(), body.to(), body.q()),
                body.exceptIds() == null ? Set.of() : body.exceptIds(), body.expectedParcels(), body.expectedAmount()));
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
