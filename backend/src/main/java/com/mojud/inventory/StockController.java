package com.mojud.inventory;

import com.mojud.shared.error.ApiException;
import com.mojud.shared.idempotency.Idempotency;
import com.mojud.tenancy.BusinessRole;
import com.mojud.tenancy.RequiresRole;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Stock adjustments. Every member may adjust stock (backend-plan.md §7: staff receive deliveries
 * and count shelves). A variant's history is served by the catalog's variant endpoints, which know
 * the variant exists (StockQueries#history).
 */
@RestController
@RequiresRole(BusinessRole.STAFF)
class StockController {

    static final int MAX_QUANTITY = 1_000_000;

    private final AdjustmentService adjustments;

    StockController(AdjustmentService adjustments) {
        this.adjustments = adjustments;
    }

    record LineRequest(@NotNull UUID variantId, @NotNull AdjustmentService.Kind kind,
            @NotNull @PositiveOrZero @Max(MAX_QUANTITY) Integer quantity) {
    }

    /** {@code note} is the reason in the seller's words, e.g. "Delivery from Islampur". */
    record AdjustmentRequest(@NotEmpty @Size(max = 200) List<@NotNull @Valid LineRequest> lines,
            @Size(max = 500) String note) {
    }

    /**
     * Several variants at once: RECEIVE and DAMAGE take a positive quantity, COUNT the counted
     * total. All lines happen or none. Send an {@code Idempotency-Key} header to make a resend safe.
     */
    @PostMapping("/api/v1/stock/adjustments")
    @ResponseStatus(HttpStatus.CREATED)
    AdjustmentService.Result adjust(@Valid @RequestBody AdjustmentRequest body,
            @RequestHeader(name = Idempotency.HEADER, required = false) String idempotencyKey) {
        for (LineRequest line : body.lines()) {
            if (line.kind() != AdjustmentService.Kind.COUNT && line.quantity() == 0) {
                throw ApiException.invalidField("lines", "jakarta.validation.constraints.Positive.message");
            }
        }
        String note = body.note() == null || body.note().isBlank() ? null : body.note().strip();
        return adjustments.adjust(new AdjustmentService.Request(body.lines().stream()
                .map(l -> new AdjustmentService.LineRequest(l.variantId(), l.kind(), l.quantity())).toList(), note),
                idempotencyKey);
    }
}
