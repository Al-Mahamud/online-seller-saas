package com.mojud.billing.system;

import java.util.UUID;

/** Moves one business's subscription along its lifecycle as of now, and sends what reminders are due. */
public interface SubscriptionAdvancer {

    void advance(UUID businessId);
}
