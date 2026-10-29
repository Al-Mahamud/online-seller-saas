package com.mojud.identity.twofactor;

import com.mojud.shared.crypto.SecretBox;
import com.mojud.shared.error.ApiException;
import com.mojud.shared.error.ErrorCode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Two-step sign-in with an authenticator app (TOTP, implementation-plan.md 13a): optional for every
 * account, required for platform admins. Set up in two steps, so it is on only once the app is
 * known to produce codes: {@link #setup} makes a secret to scan, {@link #enable} takes a code from
 * it and returns ten one-time recovery codes for when the phone is lost.
 *
 * <p>The secret is stored sealed ({@link SecretBox}). A code is accepted for the current 30-second
 * step and one either side (phone clocks drift), and only once: the step used is remembered.
 * Guessing is limited by the callers (sign-in failure limits).
 */
@Service
public class TwoFactor {

    public record Status(boolean enabled, Instant enabledAt, int recoveryCodesLeft) {
    }

    /** {@code secret} in base32, to type in; {@code otpauthUri} for a QR code. */
    public record Setup(String secret, String otpauthUri) {
    }

    /** How a code was accepted: from the app, or as a recovery code (with how many are left). */
    public record Verified(boolean recoveryCode, int recoveryCodesLeft) {
    }

    static final int RECOVERY_CODES = 10;
    private static final String RECOVERY_ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";
    private static final Pattern SIX_DIGITS = Pattern.compile("\\d{6}");
    private static final Pattern RECOVERY = Pattern.compile("[" + RECOVERY_ALPHABET + "]{10}");
    private static final SecureRandom RANDOM = new SecureRandom();

    private record Row(byte[] secret, Instant enabledAt, Long lastStep) {
    }

    private static final Logger log = LoggerFactory.getLogger(TwoFactor.class);

    private final JdbcClient jdbc;
    private final SecretBox box;
    private final Clock clock;

    TwoFactor(DataSource dataSource, SecretBox box, Clock clock) {
        this.jdbc = JdbcClient.create(dataSource);
        this.box = box;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Status status(UUID userId) {
        Optional<Row> row = row(userId, false).filter(r -> r.enabledAt() != null);
        return new Status(row.isPresent(), row.map(Row::enabledAt).orElse(null), row.isPresent() ? codesLeft(userId) : 0);
    }

    /**
     * A new secret for the app, replacing an unfinished setup. {@code accountName} is what the app
     * shows under "Mojud" (the phone number).
     */
    @Transactional
    public Setup setup(UUID userId, String accountName) {
        if (!box.available()) {
            throw new ApiException(ErrorCode.TWO_FACTOR_UNAVAILABLE);
        }
        if (row(userId, true).filter(r -> r.enabledAt() != null).isPresent()) {
            throw new ApiException(ErrorCode.TWO_FACTOR_ALREADY_ENABLED);
        }
        byte[] secret = Totp.newSecret();
        Timestamp now = Timestamp.from(clock.instant());
        jdbc.sql("""
                        insert into two_factor (user_id, secret, enabled_at, last_step, created_at, updated_at)
                        values (?, ?, null, null, ?, ?)
                        on conflict (user_id) do update set secret = excluded.secret, last_step = null, created_at = excluded.created_at,
                                                            updated_at = excluded.updated_at
                        """)
                .params(userId, box.seal(secret, owner(userId)), now, now)
                .update();
        String base32 = Totp.base32(secret);
        String label = URLEncoder.encode("Mojud:" + accountName, StandardCharsets.UTF_8).replace("+", "%20");
        return new Setup(base32, "otpauth://totp/" + label + "?secret=" + base32
                + "&issuer=Mojud&algorithm=SHA1&digits=6&period=" + Totp.PERIOD_SECONDS);
    }

    /** Turns it on with a code from the app; returns the recovery codes, shown this once. */
    @Transactional(noRollbackFor = ApiException.class)
    public List<String> enable(UUID userId, String code) {
        Row row = row(userId, true).orElseThrow(() -> new ApiException(ErrorCode.TWO_FACTOR_SETUP_MISSING));
        if (row.enabledAt() != null) {
            throw new ApiException(ErrorCode.TWO_FACTOR_ALREADY_ENABLED);
        }
        if (!acceptAppCode(userId, row, code)) {
            throw invalid();
        }
        jdbc.sql("update two_factor set enabled_at = ?, updated_at = ? where user_id = ?")
                .params(Timestamp.from(clock.instant()), Timestamp.from(clock.instant()), userId).update();
        return newRecoveryCodes(userId);
    }

    /** True when the user has it on. */
    @Transactional(readOnly = true)
    public boolean isEnabled(UUID userId) {
        return row(userId, false).filter(r -> r.enabledAt() != null).isPresent();
    }

    /**
     * Accepts a code from the app, or a recovery code (used up). A failed check is kept (the step
     * remembered is unchanged; nothing to roll back).
     */
    @Transactional(noRollbackFor = ApiException.class)
    public Verified verify(UUID userId, String code) {
        Row row = row(userId, true).filter(r -> r.enabledAt() != null)
                .orElseThrow(() -> new ApiException(ErrorCode.TWO_FACTOR_NOT_ENABLED));
        if (code != null && SIX_DIGITS.matcher(code.strip()).matches()) {
            if (acceptAppCode(userId, row, code.strip())) {
                return new Verified(false, codesLeft(userId));
            }
            throw invalid();
        }
        String recovery = normalise(code);
        if (recovery != null && jdbc.sql("""
                        update two_factor_recovery_codes set used_at = ?
                        where user_id = ? and code_hash = ? and used_at is null
                        """)
                .params(Timestamp.from(clock.instant()), userId, hash(userId, recovery)).update() == 1) {
            return new Verified(true, codesLeft(userId));
        }
        throw invalid();
    }

    /** New recovery codes (the old ones stop working), with a code from the app. */
    @Transactional(noRollbackFor = ApiException.class)
    public List<String> replaceRecoveryCodes(UUID userId, String appCode) {
        Row row = row(userId, true).filter(r -> r.enabledAt() != null)
                .orElseThrow(() -> new ApiException(ErrorCode.TWO_FACTOR_NOT_ENABLED));
        if (!acceptAppCode(userId, row, appCode)) {
            throw invalid();
        }
        return newRecoveryCodes(userId);
    }

    /**
     * After the encryption key was rotated (SecretBox), re-seals every authenticator secret still
     * sealed with an old key, at start-up; then the old key can be removed (docs/operations.md).
     *
     * @return how many were re-sealed
     */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public int resealWithCurrentKey() {
        if (!box.available() || !box.hasPreviousKeys()) {
            return 0;
        }
        record Sealed(UUID userId, byte[] secret) {
        }
        int resealed = 0;
        for (Sealed row : jdbc.sql("select user_id, secret from two_factor for update")
                .query((rs, n) -> new Sealed(rs.getObject(1, UUID.class), rs.getBytes(2))).list()) {
            if (!box.sealedWithCurrentKey(row.secret(), owner(row.userId()))) {
                jdbc.sql("update two_factor set secret = ? where user_id = ?")
                        .params(box.seal(box.open(row.secret(), owner(row.userId())), owner(row.userId())), row.userId())
                        .update();
                resealed++;
            }
        }
        log.info("Re-sealed {} authenticator secret(s) with the current encryption key", resealed);
        return resealed;
    }

    /** Turns it off (the caller has checked the password and a code). */
    @Transactional
    public void disable(UUID userId) {
        jdbc.sql("delete from two_factor_recovery_codes where user_id = ?").param(userId).update();
        jdbc.sql("delete from two_factor where user_id = ?").param(userId).update();
    }

    private boolean acceptAppCode(UUID userId, Row row, String code) {
        if (code == null || !SIX_DIGITS.matcher(code).matches()) {
            return false;
        }
        byte[] secret = box.open(row.secret(), owner(userId));
        long now = Totp.step(clock.instant());
        for (long step = now - 1; step <= now + 1; step++) {
            if (row.lastStep() != null && step <= row.lastStep()) {
                continue;
            }
            if (MessageDigest.isEqual(Totp.code(secret, step).getBytes(StandardCharsets.US_ASCII),
                    code.getBytes(StandardCharsets.US_ASCII))) {
                // The row is locked: no other request can use this step in between.
                jdbc.sql("update two_factor set last_step = ?, updated_at = ? where user_id = ?")
                        .params(step, Timestamp.from(clock.instant()), userId).update();
                return true;
            }
        }
        return false;
    }

    private List<String> newRecoveryCodes(UUID userId) {
        jdbc.sql("delete from two_factor_recovery_codes where user_id = ?").param(userId).update();
        List<String> codes = new ArrayList<>();
        Timestamp now = Timestamp.from(clock.instant());
        while (codes.size() < RECOVERY_CODES) {
            StringBuilder code = new StringBuilder();
            for (int i = 0; i < 10; i++) {
                code.append(RECOVERY_ALPHABET.charAt(RANDOM.nextInt(RECOVERY_ALPHABET.length())));
            }
            if (jdbc.sql("""
                            insert into two_factor_recovery_codes (user_id, code_hash, used_at, created_at) values (?, ?, null, ?)
                            on conflict do nothing
                            """)
                    .params(userId, hash(userId, code.toString()), now).update() == 1) {
                codes.add(code.substring(0, 5) + "-" + code.substring(5));
            }
        }
        return codes;
    }

    private int codesLeft(UUID userId) {
        return jdbc.sql("select count(*) from two_factor_recovery_codes where user_id = ? and used_at is null")
                .param(userId).query(Integer.class).single();
    }

    private Optional<Row> row(UUID userId, boolean lock) {
        return jdbc.sql("select secret, enabled_at, last_step from two_factor where user_id = ?" + (lock ? " for update" : ""))
                .param(userId)
                .query((rs, n) -> new Row(rs.getBytes(1), rs.getTimestamp(2) == null ? null : rs.getTimestamp(2).toInstant(),
                        rs.getObject(3, Long.class)))
                .optional();
    }

    /** Recovery codes as typed: any case, with or without the dash and spaces. */
    private static String normalise(String code) {
        if (code == null) {
            return null;
        }
        String cleaned = code.replaceAll("[\\s-]", "").toUpperCase(Locale.ROOT);
        return RECOVERY.matcher(cleaned).matches() ? cleaned : null;
    }

    private static String hash(UUID userId, String code) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((userId + ":" + code).getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] owner(UUID userId) {
        return ("two_factor:" + userId).getBytes(StandardCharsets.US_ASCII);
    }

    private static ApiException invalid() {
        return new ApiException(ErrorCode.TWO_FACTOR_INVALID_CODE);
    }
}
