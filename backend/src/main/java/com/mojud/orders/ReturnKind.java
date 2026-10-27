package com.mojud.orders;

enum ReturnKind {
    /** The parcel came back: nothing was delivered. */
    FAILED_DELIVERY,
    /** Delivered, but the customer refused some items at the door. */
    PARTIAL_DELIVERY,
    /** Sent back after delivery. */
    CUSTOMER_RETURN
}
