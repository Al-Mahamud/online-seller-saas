package com.mojud.tenancy.staff;

import com.mojud.shared.email.EmailComposer;
import com.mojud.support.IntegrationTest;
import com.mojud.support.Snapshots;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The invite email in both languages (implementation-plan.md 13a), against reviewed snapshots.
 * The English shop name has markup in it, which must arrive as text.
 */
@IntegrationTest
class InviteEmailTests {

    @Autowired
    EmailComposer composer;

    @Test
    void theInviteEmailMatchesItsSnapshots() {
        String link = "https://app.mojud.example/invites#Zx81-token";
        Snapshots.matchesEmail("invite-en", InviteService.email(composer.in(Locale.ENGLISH),
                new Object[] {"Rina Akter", "Rina's <b>Boutique</b>", "Manager", link, 7L}, link, 7));
        Snapshots.matchesEmail("invite-bn", InviteService.email(composer.in(Locale.forLanguageTag("bn")),
                new Object[] {"রিনা আক্তার", "রিনার বুটিক", "ম্যানেজার", link, 7L}, link, 7));
    }
}
