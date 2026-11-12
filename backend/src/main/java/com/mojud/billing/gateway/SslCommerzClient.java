package com.mojud.billing.gateway;

import com.mojud.shared.logging.LoggingProperties;
import com.mojud.shared.logging.OutboundLog;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * SSLCommerz's APIs (backend-plan.md §9): the checkout session, the validation of a payment by its
 * {@code val_id}, and the query of a transaction by our {@code tran_id}. Settings are read on each
 * call ({@code mojud.billing.sslcommerz.*}; credentials from SSLCOMMERZ_STORE_ID and
 * SSLCOMMERZ_STORE_PASSWORD), so tests can point it at a fake server on the app's own port.
 */
@Component
public class SslCommerzClient {

    /** The gateway's answer to a validation or a query, with the fields we check, and as received. */
    public record Payment(String status, String tranId, String valId, String amount, String currency, String raw) {

        /** VALID (first check) or VALIDATED (checked before): either way, paid. */
        public boolean paid() {
            return "VALID".equals(status) || "VALIDATED".equals(status);
        }
    }

    /** {@code gatewayUrl} null when the gateway refused, with its reason. */
    public record Session(String gatewayUrl, String failedReason) {
    }

    private final Environment environment;
    private final JsonMapper json;
    private final RestClient http;

    SslCommerzClient(Environment environment, JsonMapper json, LoggingProperties logging) {
        this.environment = environment;
        this.json = json;
        SimpleClientHttpRequestFactory requests = new SimpleClientHttpRequestFactory();
        requests.setConnectTimeout(Duration.ofSeconds(5));
        requests.setReadTimeout(Duration.ofSeconds(20));
        // Each call and its answer in the log, the store password masked ([REQ] and [RES]).
        this.http = RestClient.builder().requestFactory(requests)
                .requestInterceptor(new OutboundLog(logging.bodyLimit())).build();
    }

    /** False until the store's credentials are set: checkout then answers 503. */
    public boolean configured() {
        return !storeId().isBlank() && !password().isBlank();
    }

    /** Starts a hosted checkout; {@code fields} are the session API's (amount, tran_id, URLs, customer...). */
    public Session session(Map<String, String> fields) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("store_id", storeId());
        form.add("store_passwd", password());
        fields.forEach(form::add);
        String body = http.post().uri(baseUrl() + "/gwprocess/v4/api.php")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(String.class);
        JsonNode answer = json.readTree(body);
        if ("SUCCESS".equalsIgnoreCase(answer.path("status").asString()) && !answer.path("GatewayPageURL").asString().isBlank()) {
            return new Session(answer.path("GatewayPageURL").asString(), null);
        }
        return new Session(null, answer.path("failedreason").asString("unknown"));
    }

    /** The Validation API: what the gateway says about this {@code val_id}. Never trust a callback without it. */
    public Payment validate(String valId) {
        String body = http.get().uri(baseUrl() + "/validator/api/validationserverAPI.php?val_id={val}&store_id={id}"
                        + "&store_passwd={password}&v=1&format=json", valId, storeId(), password())
                .retrieve()
                .body(String.class);
        return payment(json.readTree(body), body);
    }

    /** The transaction query API: every payment the gateway has for our {@code tran_id}. */
    public List<Payment> query(String tranId) {
        String body = http.get().uri(baseUrl() + "/validator/api/merchantTransIDvalidationAPI.php?tran_id={tran}"
                        + "&store_id={id}&store_passwd={password}&format=json", tranId, storeId(), password())
                .retrieve()
                .body(String.class);
        JsonNode answer = json.readTree(body);
        return answer.path("element").valueStream().map(element -> payment(element, element.toString())).toList();
    }

    private static Payment payment(JsonNode node, String raw) {
        return new Payment(node.path("status").asString(""), node.path("tran_id").asString(""),
                node.path("val_id").asString(""), node.path("amount").asString(""), node.path("currency").asString(""), raw);
    }

    private String baseUrl() {
        return environment.getProperty("mojud.billing.sslcommerz.base-url", "https://sandbox.sslcommerz.com");
    }

    private String storeId() {
        return environment.getProperty("mojud.billing.sslcommerz.store-id", "");
    }

    private String password() {
        return environment.getProperty("mojud.billing.sslcommerz.store-password", "");
    }
}
