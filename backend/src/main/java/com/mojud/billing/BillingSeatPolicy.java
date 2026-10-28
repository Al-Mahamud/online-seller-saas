package com.mojud.billing;

import com.mojud.tenancy.SeatPolicy;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Staff seats from the plan (tenancy asks; billing knows the plan). */
@Component
class BillingSeatPolicy implements SeatPolicy {

    private final Entitlements entitlements;

    BillingSeatPolicy(Entitlements entitlements) {
        this.entitlements = entitlements;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void requireSeats(long staffAfter) {
        entitlements.requireWithin(Metric.STAFF_SEATS, staffAfter);
    }
}
