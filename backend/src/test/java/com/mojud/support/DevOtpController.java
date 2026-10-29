package com.mojud.support;

import com.mojud.shared.email.EmailAddress;
import com.mojud.shared.phone.PhoneNumber;
import io.swagger.v3.oas.annotations.Hidden;
import java.util.Map;
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

    private final CapturedMessages messages;

    DevOtpController(CapturedMessages messages) {
        this.messages = messages;
    }

    @GetMapping("/dev/otp")
    ResponseEntity<Map<String, String>> lastCode(@RequestParam String to) {
        String destination = to.contains("@")
                ? EmailAddress.tryParse(to).map(EmailAddress::value).orElse(to)
                : PhoneNumber.tryParse(to).map(PhoneNumber::e164).orElse(to);
        return messages.lastCode(destination)
                .map(code -> ResponseEntity.ok(Map.of("code", code)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
