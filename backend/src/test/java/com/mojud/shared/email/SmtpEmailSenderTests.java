package com.mojud.shared.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.GreenMailUtil;
import com.icegreen.greenmail.util.ServerSetupTest;
import jakarta.mail.Multipart;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.mock.env.MockEnvironment;

/** The SMTP sender against a real SMTP server running in the test (GreenMail). */
class SmtpEmailSenderTests {

    @RegisterExtension
    static GreenMailExtension smtp = new GreenMailExtension(ServerSetupTest.SMTP.dynamicPort());

    @Test
    void sendsTextWithAnHtmlAlternativeInUtf8() throws Exception {
        SmtpEmailSender sender = new SmtpEmailSender(settings().withProperty("mojud.email.from", "মজুদ <no-reply@mojud.test>"));

        sender.send(new EmailAddress("rina@example.com"),
                new EmailMessage("আপনার মজুদ কোড: 482913", "আপনার কোড 482913", "<p>আপনার কোড <b>482913</b></p>"));

        MimeMessage[] received = smtp.getReceivedMessages();
        assertThat(received).hasSize(1);
        MimeMessage message = received[0];
        assertThat(message.getSubject()).isEqualTo("আপনার মজুদ কোড: 482913");
        assertThat(((InternetAddress) message.getFrom()[0]).getPersonal()).isEqualTo("মজুদ");
        assertThat(((InternetAddress) message.getFrom()[0]).getAddress()).isEqualTo("no-reply@mojud.test");
        assertThat(message.getAllRecipients()[0].toString()).isEqualTo("rina@example.com");
        String raw = GreenMailUtil.getWholeMessage(message);
        assertThat(raw).contains("multipart/alternative").contains("text/plain").contains("text/html");
        assertThat(message.getContent()).isInstanceOf(Multipart.class);
    }

    @Test
    void aFailedSendIsLoggedNotThrown() {
        SmtpEmailSender sender = new SmtpEmailSender(settings().withProperty("mojud.email.smtp.port", "1")
                .withProperty("mojud.email.from", "Mojud <no-reply@mojud.test>"));
        sender.send(new EmailAddress("rina@example.com"), new EmailMessage("s", "t", "<p>t</p>"));
        assertThat(smtp.getReceivedMessages()).isEmpty();
    }

    @Test
    void missingSettingsStopTheAppFromStarting() {
        assertThatThrownBy(() -> new SmtpEmailSender(new MockEnvironment().withProperty("mojud.email.from", "a@b.c")))
                .hasMessageContaining("mojud.email.smtp.host");
        assertThatThrownBy(() -> new SmtpEmailSender(settings())).hasMessageContaining("mojud.email.from");
    }

    private static MockEnvironment settings() {
        return new MockEnvironment()
                .withProperty("mojud.email.smtp.host", "127.0.0.1")
                .withProperty("mojud.email.smtp.port", Integer.toString(smtp.getSmtp().getPort()))
                .withProperty("mojud.email.smtp.starttls", "false");
    }
}
