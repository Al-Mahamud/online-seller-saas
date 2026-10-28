package com.mojud.billing.system;

import com.mojud.billing.gateway.SslCommerzClient;
import java.util.UUID;

/** Applies the gateway's validated answer to an attempt, as the attempt's business. */
public interface PaymentConfirmations {

    /** Checks the validated payment against the attempt; marks the invoice paid once. */
    void confirm(UUID businessId, String tranId, SslCommerzClient.Payment validated);

    /** A bank transfer an admin has seen arrive: the invoice is paid through the same steps. */
    void markBankTransfer(UUID businessId, UUID invoiceId, String reference, UUID adminId);

    /** The gateway says it did not happen (FAILED, CANCELLED, or expired unanswered). */
    void failed(UUID businessId, String tranId, String status);
}
