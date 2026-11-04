package com.mojud.notifications;

import com.mojud.tenancy.BusinessRole;
import com.mojud.tenancy.CurrentBusiness;
import com.mojud.tenancy.RequiresRole;
import com.mojud.tenancy.RequiresPermission;
import com.mojud.tenancy.Permission;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.sql.Timestamp;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The signed-in member's email choices in the selected business. Only the daily low-stock digest
 * (to managers and owners) can be turned off; billing and security emails always go.
 */
@RestController
@RequiresRole(BusinessRole.STAFF)
@RequiresPermission(Permission.SETTINGS)
class NotificationPreferencesController {

    record Preferences(@NotNull Boolean lowStockDigestEmail) {
    }

    private final JdbcClient jdbc;
    private final Clock clock;

    NotificationPreferencesController(DataSource dataSource, Clock clock) {
        this.jdbc = JdbcClient.create(dataSource);
        this.clock = clock;
    }

    @GetMapping("/api/v1/notifications/preferences")
    @Transactional(readOnly = true)
    Preferences get() {
        CurrentBusiness me = CurrentBusiness.require();
        boolean digest = jdbc.sql("""
                        select email from notification_preferences where business_id = ? and user_id = ? and kind = ?
                        """)
                .params(me.businessId(), me.userId(), MemberMail.LOW_STOCK_DIGEST)
                .query(Boolean.class).optional().orElse(true);
        return new Preferences(digest);
    }

    @PutMapping("/api/v1/notifications/preferences")
    @Transactional
    Preferences put(@Valid @RequestBody Preferences body) {
        CurrentBusiness me = CurrentBusiness.require();
        jdbc.sql("""
                        insert into notification_preferences (business_id, user_id, kind, email, updated_at)
                        values (?, ?, ?, ?, ?)
                        on conflict (business_id, user_id, kind) do update set email = excluded.email, updated_at = excluded.updated_at
                        """)
                .params(me.businessId(), me.userId(), MemberMail.LOW_STOCK_DIGEST, body.lowStockDigestEmail(),
                        Timestamp.from(clock.instant()))
                .update();
        return body;
    }
}
