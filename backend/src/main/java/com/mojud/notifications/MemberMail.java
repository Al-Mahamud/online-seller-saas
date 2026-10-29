package com.mojud.notifications;

import com.mojud.identity.UserDirectory;
import com.mojud.shared.async.AfterCommitTasks;
import com.mojud.shared.email.EmailMessage;
import com.mojud.shared.email.EmailSender;
import com.mojud.shared.tenant.TenantScope;
import com.mojud.tenancy.BusinessMembers;
import com.mojud.tenancy.BusinessRole;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Emails members of the business being worked in (implementation-plan.md 13a): those with a role
 * at least {@code audience}'s and a verified email, each in their own language, after the
 * caller's transaction commits. The caller writes the email (in its module, for one language at
 * a time); each language is written once. Without an email sender configured, nothing is sent.
 */
@Component
public class MemberMail {

    /** The emails a member can turn off ({@code notification_preferences.kind}). */
    public static final String LOW_STOCK_DIGEST = "stock.low_digest";

    private final BusinessMembers members;
    private final UserDirectory users;
    private final ObjectProvider<EmailSender> sender;
    private final AfterCommitTasks afterCommit;
    private final JdbcClient jdbc;

    MemberMail(BusinessMembers members, UserDirectory users, ObjectProvider<EmailSender> sender,
            AfterCommitTasks afterCommit, DataSource dataSource) {
        this.members = members;
        this.users = users;
        this.sender = sender;
        this.afterCommit = afterCommit;
        this.jdbc = JdbcClient.create(dataSource);
    }

    /**
     * @param preference a kind members may have turned off ({@link #LOW_STOCK_DIGEST}), or null for
     *        an email that always goes (billing)
     * @return how many members it goes to
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public int send(BusinessRole audience, String preference, Function<Locale, EmailMessage> compose) {
        EmailSender email = sender.getIfAvailable();
        if (email == null) {
            return 0;
        }
        List<UUID> recipients = members.withRoleAtLeast(audience);
        if (preference != null && !recipients.isEmpty()) {
            Set<UUID> off = Set.copyOf(jdbc.sql("""
                            select user_id from notification_preferences
                            where business_id = :business and kind = :kind and not email and user_id in (:users)
                            """)
                    .param("business", TenantScope.current().orElseThrow())
                    .param("kind", preference)
                    .param("users", recipients)
                    .query(UUID.class).list());
            recipients = recipients.stream().filter(user -> !off.contains(user)).toList();
        }
        if (recipients.isEmpty()) {
            return 0;
        }
        Map<Locale, EmailMessage> byLanguage = new HashMap<>();
        Map<UUID, UserDirectory.EmailContact> contacts = users.emailContacts(recipients);
        for (UserDirectory.EmailContact contact : contacts.values()) {
            EmailMessage message = byLanguage.computeIfAbsent(contact.locale(), compose);
            afterCommit.run(() -> email.send(contact.email(), message));
        }
        return contacts.size();
    }
}
