package com.mojud.tenancy;

import java.util.UUID;

/** How the business presents itself on paper (packing slips, invoices), for the business being worked in. */
public interface BusinessProfile {

    /** {@code phone} in E.164 or null; {@code logoFileId} null without a logo. */
    record Profile(String name, String phone, String address, UUID logoFileId, String invoiceFooter) {
    }

    Profile current();
}
