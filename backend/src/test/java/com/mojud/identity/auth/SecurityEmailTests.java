package com.mojud.identity.auth;

import com.mojud.shared.email.EmailComposer;
import com.mojud.support.IntegrationTest;
import com.mojud.support.Snapshots;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Sign-in security emails in both languages (implementation-plan.md 13a), against reviewed snapshots. */
@IntegrationTest
class SecurityEmailTests {

    static final String WEB = "https://app.mojud.example";
    static final Instant AT = Instant.parse("2026-10-28T15:58:00Z");

    @Autowired
    EmailComposer composer;

    @Test
    void securityEmailsMatchTheirSnapshots() {
        for (String lang : List.of("en", "bn")) {
            Locale locale = Locale.forLanguageTag(lang);
            Snapshots.matchesEmail("two-factor-on-" + lang,
                    SecurityEmails.email(composer.in(locale), "email.security.two_factor_on", AT, WEB));
            Snapshots.matchesEmail("two-factor-off-" + lang,
                    SecurityEmails.email(composer.in(locale), "email.security.two_factor_off", AT, WEB));
            Snapshots.matchesEmail("recovery-code-used-" + lang,
                    SecurityEmails.email(composer.in(locale), "email.security.recovery_code_used", AT, WEB, 3));
            Snapshots.matchesEmail("recovery-codes-gone-" + lang,
                    SecurityEmails.email(composer.in(locale), "email.security.recovery_code_used", AT, WEB, 0));
        }
    }
}
