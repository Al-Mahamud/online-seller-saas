package com.mojud.identity.otp;

import com.mojud.shared.email.EmailComposer;
import com.mojud.support.IntegrationTest;
import com.mojud.support.Snapshots;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** The one-time code email in both languages (implementation-plan.md 13a), against reviewed snapshots. */
@IntegrationTest
class OtpEmailTests {

    @Autowired
    EmailComposer composer;

    @Test
    void theCodeEmailMatchesItsSnapshots() {
        for (String lang : List.of("en", "bn")) {
            Snapshots.matchesEmail("otp-" + lang, OtpService.email(composer.in(Locale.forLanguageTag(lang)), "482913", 5));
        }
    }
}
