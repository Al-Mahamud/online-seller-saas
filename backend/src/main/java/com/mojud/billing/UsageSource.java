package com.mojud.billing;

/**
 * A module that counts a live metric for billing (the catalog counts variants), in the business of
 * the TenantScope. Billing finds the implementations; it does not depend on the modules counting.
 */
public interface UsageSource {

    Metric metric();

    long used();
}
