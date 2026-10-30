package com.mojud.support;

import com.mojud.shared.email.EmailAddress;
import com.mojud.shared.phone.PhoneNumber;
import io.swagger.v3.oas.annotations.Hidden;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /dev/otp?to=01712345678} returns the last code sent there. Exists only in test
 * sources, so only in tests and {@code spring-boot:test-run}; the web app and end-to-end tests use
 * it to sign up without a real SMS or email.
 */
// Test-only: kept out of the API description the frontend is generated from.
@Hidden
@RestController
class DevOtpController {

    private static final Pattern INVITE_LINK = Pattern.compile("/invites#([^\\s\"<]+)");

    private final CapturedMessages messages;

    DevOtpController(CapturedMessages messages) {
        this.messages = messages;
    }

    @GetMapping("/dev/otp")
    ResponseEntity<Map<String, String>> lastCode(@RequestParam String to) {
        return messages.lastCode(destination(to))
                .map(code -> ResponseEntity.ok(Map.of("code", code)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** The invite token in the newest message sent there, for end-to-end tests of the invite link. */
    @GetMapping("/dev/invite")
    ResponseEntity<Map<String, String>> lastInvite(@RequestParam String to) {
        List<CapturedMessages.Message> received = messages.to(destination(to));
        if (!received.isEmpty()) {
            Matcher link = INVITE_LINK.matcher(received.get(received.size() - 1).body());
            if (link.find()) {
                return ResponseEntity.ok(Map.of("token", link.group(1)));
            }
        }
        return ResponseEntity.notFound().build();
    }

    private static String destination(String to) {
        return to.contains("@")
                ? EmailAddress.tryParse(to).map(EmailAddress::value).orElse(to)
                : PhoneNumber.tryParse(to).map(PhoneNumber::e164).orElse(to);
    }
}
