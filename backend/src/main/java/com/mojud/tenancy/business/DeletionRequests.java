package com.mojud.tenancy.business;

import com.mojud.identity.PasswordConfirmation;
import com.mojud.shared.audit.AuditTrail;
import com.mojud.shared.error.ApiException;
import com.mojud.shared.error.ErrorCode;
import com.mojud.tenancy.CurrentBusiness;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The owner deleting their business (implementation-plan.md 12b): asked for with the password, done
 * 7 days later by the daily job (tenancy.system.BusinessDeletions), cancellable until then. The
 * business works as usual meanwhile, so its data can still be exported.
 */
@Service
class DeletionRequests {

    static final Duration UNDO = Duration.ofDays(7);

    private final JdbcClient jdbc;
    private final PasswordConfirmation passwords;
    private final AuditTrail audit;
    private final Clock clock;

    DeletionRequests(DataSource dataSource, PasswordConfirmation passwords, AuditTrail audit, Clock clock) {
        this.jdbc = JdbcClient.create(dataSource);
        this.passwords = passwords;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    Instant request(String password, String clientIp) {
        CurrentBusiness me = CurrentBusiness.require();
        passwords.confirm(me.userId(), password, clientIp);
        Instant now = clock.instant();
        Instant due = now.plus(UNDO);
        int changed = jdbc.sql("""
                        update businesses set deletion_requested_at = ?, deletion_requested_by = ?, deletion_due_at = ?
                        where id = ? and deletion_due_at is null
                        """)
                .params(Timestamp.from(now), me.userId(), Timestamp.from(due), me.businessId())
                .update();
        if (changed == 0) {
            throw new ApiException(ErrorCode.BUSINESS_DELETION_PENDING);
        }
        audit.record(me.businessId(), me.userId(), "business.deletion_requested", "business", me.businessId(), "due", due);
        return due;
    }

    @Transactional
    void cancel() {
        CurrentBusiness me = CurrentBusiness.require();
        int changed = jdbc.sql("""
                        update businesses set deletion_requested_at = null, deletion_requested_by = null, deletion_due_at = null
                        where id = ? and deletion_due_at is not null
                        """)
                .param(me.businessId())
                .update();
        if (changed == 0) {
            throw ApiException.notFound();
        }
        audit.record(me.businessId(), me.userId(), "business.deletion_cancelled", "business", me.businessId());
    }
}
