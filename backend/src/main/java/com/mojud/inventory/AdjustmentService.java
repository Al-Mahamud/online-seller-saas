package com.mojud.inventory;

import com.mojud.shared.audit.AuditTrail;
import com.mojud.shared.error.ApiException;
import com.mojud.shared.id.Ids;
import com.mojud.shared.idempotency.Idempotency;
import com.mojud.tenancy.CurrentBusiness;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stock changes people make by hand (implementation-plan.md 4a): stock received, damaged, or
 * counted. One request may change several variants; it happens completely or not at all, is safe
 * to resend with an Idempotency-Key, and is written to the audit log.
 */
@Service
class AdjustmentService {

    /** RECEIVE adds {@code quantity}; DAMAGE removes it; COUNT sets on hand to it. */
    enum Kind {
        RECEIVE,
        DAMAGE,
        COUNT
    }

    record LineRequest(UUID variantId, Kind kind, int quantity) {
    }

    /** {@code reason} is kept on the damage lines only. */
    record Request(List<LineRequest> lines, String note, DamageReason reason) {
    }

    record LineResult(UUID variantId, Kind kind, int quantity, int onHand, int reserved, int available) {
    }

    record Result(UUID id, List<LineResult> lines) {
    }

    private final StockService stock;
    private final Idempotency idempotency;
    private final AuditTrail audit;

    AdjustmentService(StockService stock, Idempotency idempotency, AuditTrail audit) {
        this.stock = stock;
        this.idempotency = idempotency;
        this.audit = audit;
    }

    @Transactional
    Result adjust(Request request, String idempotencyKey) {
        Set<UUID> seen = new HashSet<>();
        for (LineRequest line : request.lines()) {
            if (!seen.add(line.variantId())) {
                // One line per variant keeps "counted 5, then received 2" from depending on order.
                throw ApiException.invalidField("lines", "field.variant_repeated");
            }
        }
        return idempotency.run(idempotencyKey, request, Result.class, () -> {
            UUID batch = Ids.newId();
            StockService.Reference reference = StockService.Reference.adjustment(batch, request.note(), request.reason());
            List<LineResult> results = new ArrayList<>();
            for (LineRequest line : request.lines()) {
                List<StockService.Line> one = List.of(new StockService.Line(line.variantId(), line.quantity()));
                StockLevel after = switch (line.kind()) {
                    case RECEIVE -> stock.receive(one, reference).get(0);
                    case DAMAGE -> stock.damage(one, reference).get(0);
                    case COUNT -> stock.count(line.variantId(), line.quantity(), reference).get(0);
                };
                results.add(new LineResult(line.variantId(), line.kind(), line.quantity(), after.onHand(),
                        after.reserved(), after.available()));
            }
            CurrentBusiness business = CurrentBusiness.require();
            audit.record(business.businessId(), business.userId(), "stock.adjusted", "adjustment", batch,
                    "note", request.note(),
                    "reason", request.reason(),
                    "lines", results.stream().map(r -> Map.of("variantId", r.variantId().toString(), "kind", r.kind().name(),
                            "quantity", r.quantity(), "onHandAfter", r.onHand())).toList());
            return new Result(batch, results);
        });
    }
}
