package com.mojud.tenancy;

/**
 * How many staff seats the business may use, decided outside tenancy (billing implements it), so
 * tenancy can check before adding staff without depending on billing.
 */
public interface SeatPolicy {

    /**
     * @param staffAfter staff and managers (with invites still open) once the change is made; the owner is not counted
     * @throws com.mojud.shared.error.ApiException 402 {@code plan.limit_reached} over the plan's seats
     */
    void requireSeats(long staffAfter);
}
