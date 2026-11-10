package com.mojud.billing.system;

import com.mojud.billing.gateway.SslCommerzClient;
import com.mojud.shared.db.system.SystemDatabase;
import com.mojud.shared.id.Ids;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

/**
 * What SSLCommerz sends us (backend-plan.md §9). Nobody is signed in and there is no CSRF token, so
 * nothing in a callback is believed: each is stored as received, then the payment is asked of the
 * gateway's Validation API by its val_id before anything changes. Found across businesses by the
 * attempt's tran_id (system role); applied as that business.
 */
@RestController
class GatewayCallbackController {

    private static final Logger log = LoggerFactory.getLogger(GatewayCallbackController.class);

    private record AttemptRef(UUID businessId, UUID invoiceId) {
    }

    private final SystemDatabase database;
    private final SslCommerzClient gateway;
    private final PaymentConfirmations confirmations;
    private final JsonMapper json;
    private final Clock clock;
    private final String webBaseUrl;

    GatewayCallbackController(SystemDatabase database, SslCommerzClient gateway, PaymentConfirmations confirmations,
            JsonMapper json, Clock clock, @Value("${mojud.web.base-url}") String webBaseUrl) {
        this.database = database;
        this.gateway = gateway;
        this.confirmations = confirmations;
        this.json = json;
        this.clock = clock;
        this.webBaseUrl = webBaseUrl;
    }

    /**
     * The Instant Payment Notification. 200 once handled (an unknown tran_id too, so the gateway
     * stops retrying); an error answers 500, so the gateway tries again (and the pending check will).
     */
    @PostMapping("/api/v1/public/gateway/sslcommerz/ipn")
    ResponseEntity<String> ipn(HttpServletRequest request) {
        Map<String, String> fields = fields(request);
        String tranId = fields.getOrDefault("tran_id", "");
        store("IPN", tranId, fields);
        Optional<AttemptRef> attempt = attempt(tranId);
        if (attempt.isEmpty()) {
            log.warn("[SSLCOMMERZ-IPN] unknown tran_id {}", tranId);
            return ResponseEntity.ok("UNKNOWN");
        }
        String status = fields.getOrDefault("status", "");
        if (!status.equals("VALID") && !status.equals("VALIDATED")) {
            confirmations.failed(attempt.get().businessId(), tranId, status);
            return ResponseEntity.ok("NOTED");
        }
        SslCommerzClient.Payment validated = gateway.validate(fields.getOrDefault("val_id", ""));
        confirmations.confirm(attempt.get().businessId(), tranId, validated);
        return ResponseEntity.ok("OK");
    }

    /**
     * The browser coming back (success, fail or cancel). Proves nothing by itself: it is stored, and
     * the browser goes on to the web app, which waits for the invoice's status. On success its
     * val_id is asked of the Validation API at once, as an IPN's is, so the invoice need not wait
     * for the IPN (which never reaches a local run). Only the gateway's own "paid" for this
     * attempt's tran_id is applied; anything else is left to the IPN and the pending check.
     */
    @RequestMapping(path = "/api/v1/public/gateway/sslcommerz/return/{result}", method = {RequestMethod.GET, RequestMethod.POST})
    ResponseEntity<Void> back(@PathVariable String result, @RequestParam(required = false) UUID invoice,
            HttpServletRequest request) {
        String outcome = switch (result) {
            case "success", "fail", "cancel" -> result;
            default -> "fail";
        };
        Map<String, String> fields = fields(request);
        String tranId = fields.getOrDefault("tran_id", "");
        store(outcome.toUpperCase(), tranId, fields);
        if (outcome.equals("success")) {
            confirmOnReturn(tranId, fields.getOrDefault("val_id", ""));
        }
        String target = webBaseUrl + "/billing/return?result=" + outcome + (invoice == null ? "" : "&invoice=" + invoice);
        return ResponseEntity.status(HttpStatus.SEE_OTHER).location(URI.create(target)).build();
    }

    private void confirmOnReturn(String tranId, String valId) {
        if (valId.isBlank()) {
            return;
        }
        Optional<AttemptRef> attempt = pendingAttempt(tranId);
        if (attempt.isEmpty()) {
            return;
        }
        try {
            SslCommerzClient.Payment validated = gateway.validate(valId);
            if (validated.paid() && tranId.equals(validated.tranId())) {
                confirmations.confirm(attempt.get().businessId(), tranId, validated);
            }
        } catch (RuntimeException e) {
            // The browser still goes on; the IPN or the pending check will apply the payment.
            log.warn("[SSLCOMMERZ-RETURN] checking payment {} on the browser's return failed", tranId, e);
        }
    }

    /** An SSLCommerz attempt still waiting for the gateway's word. */
    private Optional<AttemptRef> pendingAttempt(String tranId) {
        if (tranId.isEmpty()) {
            return Optional.empty();
        }
        return database.jdbc().sql("""
                        select business_id, invoice_id from payment_attempts
                        where tran_id = ? and gateway = 'SSLCOMMERZ' and status = 'PENDING'
                        """)
                .param(tranId)
                .query((rs, n) -> new AttemptRef(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)))
                .optional();
    }

    private void store(String kind, String tranId, Map<String, String> fields) {
        database.jdbc().sql("""
                        insert into gateway_callbacks (id, gateway, kind, tran_id, body, received_at)
                        values (?, 'SSLCOMMERZ', ?, ?, ?, ?)
                        """)
                .params(Ids.newId(), kind, tranId.isEmpty() ? null : tranId, json.writeValueAsString(fields),
                        Timestamp.from(clock.instant()))
                .update();
    }

    private Optional<AttemptRef> attempt(String tranId) {
        if (tranId.isEmpty()) {
            return Optional.empty();
        }
        return database.jdbc().sql("select business_id, invoice_id from payment_attempts where tran_id = ?")
                .param(tranId)
                .query((rs, n) -> new AttemptRef(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)))
                .optional();
    }

    /** The form fields as sent, one value each, in a stable order. */
    private static Map<String, String> fields(HttpServletRequest request) {
        Map<String, String> fields = new TreeMap<>();
        request.getParameterMap().forEach((name, values) -> fields.put(name, values.length == 0 ? "" : values[0]));
        return fields;
    }
}
