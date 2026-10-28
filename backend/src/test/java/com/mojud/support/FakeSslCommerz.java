package com.mojud.support;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.MediaType;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * A stand-in for SSLCommerz in tests (and local runs), answering like its session, validation and
 * transaction query APIs. Tests open sessions through checkout, then "pay" here and send the IPN
 * themselves, as the gateway would. Exists only in test sources.
 */
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
                "GatewayPageURL", "https://sandbox.sslcommerz.com/EasyCheckOut/test" + fields.get("tran_id"));
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
