package com.mojud.tenancy.staff;

import com.mojud.shared.tenant.TenantScope;
import com.mojud.tenancy.TeamSize;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Staff seats in use, in the business of the TenantScope. */
@Component
class TeamCounts implements TeamSize {

    private final JdbcClient jdbc;
    private final Clock clock;

    TeamCounts(DataSource dataSource, Clock clock) {
        this.jdbc = JdbcClient.create(dataSource);
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public long staffSeatsUsed() {
        return members() + openInvites(null);
    }

    /** Active staff and managers. */
    long members() {
        return jdbc.sql("select count(*) from memberships where business_id = ? and status = 'ACTIVE' and role <> 'OWNER'")
                .param(business()).query(Long.class).single();
    }

    /** Invites not yet answered or expired, except to {@code destination} (an invite about to be replaced). */
    long openInvites(String destination) {
        return jdbc.sql("""
                        select count(*) from invites
                        where business_id = ? and accepted_at is null and revoked_at is null and expires_at > ?
                          and destination <> ?
                        """)
                .params(business(), Timestamp.from(clock.instant()), destination == null ? "" : destination)
                .query(Long.class).single();
    }

    private static UUID business() {
        return TenantScope.current().orElseThrow();
    }
}
