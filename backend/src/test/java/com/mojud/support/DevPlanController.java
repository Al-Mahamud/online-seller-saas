package com.mojud.support;

import com.mojud.shared.db.system.SystemDatabase;
import com.mojud.shared.phone.PhoneNumber;
import io.swagger.v3.oas.annotations.Hidden;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /dev/plan?businessId=…&code=STARTER} puts one business on a plan with no payment, so
 * end-to-end tests can reach a plan's limits. Exists only in test sources.
 */
@Hidden
@RestController
class DevPlanController {

    private final SystemDatabase database;

    DevPlanController(SystemDatabase database) {
        this.database = database;
    }

    /**
     * Puts the business in a subscription status, as time and the daily job would. {@code days}: until
     * the trial, period or grace ends.
     */
    @PostMapping("/dev/subscription")
    ResponseEntity<Void> setSubscription(@RequestParam UUID businessId, @RequestParam String status,
            @RequestParam(defaultValue = "STARTER") String plan, @RequestParam(defaultValue = "10") int days) {
        Instant now = Instant.now();
        Instant ends = now.plus(Duration.ofDays(days));
        Timestamp nowAt = Timestamp.from(now);
        Timestamp endsAt = Timestamp.from(ends);
        Timestamp periodStart = Timestamp.from(ends.atZone(ZoneOffset.UTC).minusMonths(1).toInstant());
        database.jdbc().sql("""
                        update subscriptions set plan_code = ?, status = ?, suspended_from = ?,
                               trial_ends_at = ?, current_period_start = ?, current_period_end = ?, grace_ends_at = ?,
                               read_only_since = ?
                        where business_id = ?
                        """)
                .params(status.equals("TRIALING") ? "TRIAL" : plan, status, status.equals("SUSPENDED") ? "READ_ONLY" : null,
                        status.equals("TRIALING") ? endsAt : null,
                        status.equals("ACTIVE") ? periodStart : null,
                        status.equals("ACTIVE") ? endsAt : status.equals("GRACE") ? nowAt : null,
                        status.equals("GRACE") ? endsAt : null,
                        status.equals("READ_ONLY") ? nowAt : null, businessId)
                .update();
        return ResponseEntity.noContent().build();
    }

    /** Makes this phone's account a platform admin. Admin rights are read at sign-in, so sign in again after. */
    @PostMapping("/dev/platform-admin")
    ResponseEntity<Void> makeAdmin(@RequestParam String phone) {
        database.jdbc().sql("update users set is_platform_admin = true where phone = ?")
                .param(PhoneNumber.fromInput(phone, "phone").e164())
                .update();
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/dev/plan")
    ResponseEntity<Void> setPlan(@RequestParam UUID businessId, @RequestParam String code) {
        database.jdbc().sql("update subscriptions set plan_code = ? where business_id = ?")
                .params(code, businessId)
                .update();
        return ResponseEntity.noContent().build();
    }
}
