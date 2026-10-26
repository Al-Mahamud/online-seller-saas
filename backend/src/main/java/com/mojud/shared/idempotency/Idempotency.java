package com.mojud.shared.idempotency;

import com.mojud.shared.error.ApiException;
import com.mojud.shared.error.ErrorCode;
import com.mojud.shared.tenant.TenantScope;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.HexFormat;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Makes a request safe to send twice. Phones on weak networks resend: a request carrying an
 * {@code Idempotency-Key} header does its work once, and a repeat with the same key and the same
 * body gets the first answer instead of doing it again (a second stock receipt, a second order).
 * The same key with a different body is refused.
 *
 * <p>Runs in the caller's transaction, so the work and its recorded answer commit together. A
 * repeat that arrives while the first is still running waits for it.
 */
@Component
public class Idempotency {

    public static final String HEADER = "Idempotency-Key";
    private static final int MAX_KEY = 100;

    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final Clock clock;

    Idempotency(DataSource dataSource, JsonMapper json, Clock clock) {
        this.jdbc = JdbcClient.create(dataSource);
        this.json = json;
        this.clock = clock;
    }

    /**
     * Runs {@code work} once per key in the current business.
     *
     * @param key the header's value; null runs {@code work} as an ordinary request
     * @param request the request body, to tell a true repeat from a reused key
     * @throws ApiException {@code idempotency.key_reused} when the key came with a different body
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public <T> T run(String key, Object request, Class<T> responseType, Supplier<T> work) {
        if (key == null) {
            return work.get();
        }
        if (key.isBlank() || key.length() > MAX_KEY) {
            throw ApiException.invalidField(HEADER, "field.invalid_value");
        }
        java.util.UUID business = TenantScope.current().orElseThrow();
        String hash = sha256(json.writeValueAsString(request));
        int inserted = jdbc.sql("""
                        insert into idempotency_keys (business_id, key, request_hash, created_at)
                        values (?, ?, ?, ?) on conflict (business_id, key) do nothing
                        """)
                .params(business, key, hash, Timestamp.from(clock.instant()))
                .update();
        if (inserted == 1) {
            T result = work.get();
            jdbc.sql("update idempotency_keys set response_body = ?::jsonb where business_id = ? and key = ?")
                    .params(json.writeValueAsString(result), business, key)
                    .update();
            return result;
        }
        record Stored(String requestHash, String responseBody) {
        }
        Stored stored = jdbc.sql("""
                        select request_hash, response_body::text from idempotency_keys
                        where business_id = ? and key = ? for update
                        """)
                .params(business, key)
                .query((row, n) -> new Stored(row.getString(1), row.getString(2)))
                .single();
        if (!stored.requestHash().equals(hash) || stored.responseBody() == null) {
            throw new ApiException(ErrorCode.IDEMPOTENCY_KEY_REUSED);
        }
        return json.readValue(stored.responseBody(), responseType);
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }
}
