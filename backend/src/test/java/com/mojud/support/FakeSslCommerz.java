package com.mojud.support;

import io.swagger.v3.oas.annotations.Hidden;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * A stand-in for SSLCommerz in tests (and local runs), answering like its session, validation and
 * transaction query APIs. Tests open sessions through checkout, then "pay" here and send the IPN
 * themselves, as the gateway would. A browser (the web app's tests, or someone trying it locally) gets
 * a small page in place of the gateway's, whose links do all of that. Exists only in test sources.
 */
// Test-only: kept out of the API description the frontend is generated from.
@Hidden
@RestController
@RequestMapping("/api/v1/public/gateway/fake-sslcommerz")
public class FakeSslCommerz {

    /** Session requests by tran_id, as received. */
    private final Map<String, Map<String, String>> sessions = new ConcurrentHashMap<>();
    /** Payments by val_id: what the validation API answers. */
    private final Map<String, Map<String, Object>> payments = new ConcurrentHashMap<>();
    private volatile String refuseNextSession;

    @PostMapping(path = "/gwprocess/v4/api.php", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    Map<String, Object> session(@RequestBody MultiValueMap<String, String> form) {
        Map<String, String> fields = new LinkedHashMap<>();
        form.forEach((key, values) -> fields.put(key, values.get(0)));
        String reason = refuseNextSession;
        if (reason != null) {
            refuseNextSession = null;
            return Map.of("status", "FAILED", "failedreason", reason);
        }
        sessions.put(fields.get("tran_id"), fields);
        return Map.of("status", "SUCCESS", "sessionkey", "S" + fields.get("tran_id"),
                "GatewayPageURL", ServletUriComponentsBuilder.fromCurrentContextPath().toUriString()
                        + "/api/v1/public/gateway/fake-sslcommerz/checkout/" + fields.get("tran_id"));
    }

    /** The gateway's page, for a browser. Links rather than a form: the API's own headers would stop a form's redirect. */
    @GetMapping("/checkout/{tranId}")
    ResponseEntity<String> checkout(@PathVariable String tranId) {
        Map<String, String> session = sessions.get(tranId);
        if (session == null) {
            return ResponseEntity.notFound().build();
        }
        String page = """
                <!doctype html><html lang="en"><head><meta charset="utf-8"><title>Test gateway</title>
                <meta name="viewport" content="width=device-width, initial-scale=1"></head>
                <body><h1>Test gateway</h1><p>Not real money: this page exists only in test runs.</p>
                <p>Amount: BDT %s</p><p>Opens on: %s</p>
                <p><a href="%s/pay">Pay</a></p><p><a href="%s/fail">Fail</a></p><p><a href="%s/cancel">Cancel</a></p>
                </body></html>
                """.formatted(session.get("total_amount"), session.getOrDefault("multi_card_name", "every way"), tranId,
                tranId, tranId);
        return ResponseEntity.ok().contentType(new MediaType("text", "html", StandardCharsets.UTF_8))
                .header("Content-Security-Policy", "default-src 'none'").body(page);
    }

    /** The customer's choice on that page: the IPN goes out as the gateway's would, then the browser goes back. */
    @GetMapping("/checkout/{tranId}/{choice}")
    ResponseEntity<Void> choose(@PathVariable String tranId, @PathVariable String choice) {
        Map<String, String> session = sessions.get(tranId);
        if (session == null) {
            return ResponseEntity.notFound().build();
        }
        LinkedMultiValueMap<String, String> ipn = new LinkedMultiValueMap<>();
        ipn.add("tran_id", tranId);
        String back;
        switch (choice) {
            case "pay" -> {
                ipn.add("status", "VALID");
                ipn.add("val_id", pay(tranId, session.get("total_amount"), "BDT", "VALID"));
                back = session.get("success_url");
            }
            case "cancel" -> {
                ipn.add("status", "CANCELLED");
                back = session.get("cancel_url");
            }
            default -> {
                ipn.add("status", "FAILED");
                back = session.get("fail_url");
            }
        }
        RestClient.create().post().uri(session.get("ipn_url")).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(ipn).retrieve().toBodilessEntity();
        return ResponseEntity.status(HttpStatus.SEE_OTHER).header(HttpHeaders.LOCATION, back).build();
    }

    @GetMapping("/validator/api/validationserverAPI.php")
    Map<String, Object> validate(@RequestParam("val_id") String valId, @RequestParam("store_id") String storeId) {
        Map<String, Object> payment = payments.get(valId);
        if (payment == null || !"test-store".equals(storeId)) {
            return Map.of("status", "INVALID_TRANSACTION");
        }
        Map<String, Object> answer = new LinkedHashMap<>(payment);
        // The first validation says VALID, later ones VALIDATED, as SSLCommerz does.
        answer.put("status", Boolean.TRUE.equals(payment.get("validated")) ? "VALIDATED" : payment.get("status"));
        answer.remove("validated");
        payment.put("validated", true);
        return answer;
    }

    @GetMapping("/validator/api/merchantTransIDvalidationAPI.php")
    Map<String, Object> query(@RequestParam("tran_id") String tranId) {
        List<Map<String, Object>> found = payments.values().stream().filter(p -> tranId.equals(p.get("tran_id")))
                .map(p -> {
                    Map<String, Object> copy = new LinkedHashMap<>(p);
                    copy.remove("validated");
                    return copy;
                }).toList();
        return Map.of("APIConnect", "DONE", "no_of_trans_found", found.size(), "element", found);
    }

    /** The session checkout opened for this tran_id (or null). */
    public Map<String, String> session(String tranId) {
        return sessions.get(tranId);
    }

    /** The tran_id of the most recent session for this invoice (value_a). */
    public String tranIdOf(String invoiceId) {
        return sessions.values().stream().filter(s -> invoiceId.equals(s.get("value_a"))).map(s -> s.get("tran_id"))
                .reduce((a, b) -> b).orElseThrow();
    }

    /** The customer pays: returns the val_id the gateway would give. {@code status}: VALID or FAILED. */
    public String pay(String tranId, String amount, String currency, String status) {
        String valId = "V" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        Map<String, Object> payment = new LinkedHashMap<>();
        payment.put("status", status);
        payment.put("tran_id", tranId);
        payment.put("val_id", valId);
        payment.put("amount", amount);
        payment.put("currency", currency);
        payment.put("card_type", "BKASH-BKash");
        payments.put(valId, payment);
        return valId;
    }

    public void refuseNextSession(String reason) {
        refuseNextSession = reason;
    }
}
