package com.mojud.identity.auth;

import com.mojud.identity.otp.OtpDestination;
import com.mojud.shared.email.EmailAddress;
import com.mojud.shared.error.ApiException;
import com.mojud.shared.phone.PhoneNumber;

/**
 * What a user types to sign in: a phone number in any common form, or an email address. Normalised
 * to the stored form, so it doubles as the destination of a sign-in code.
 */
record LoginIdentifier(OtpDestination destination) {

    /** @throws ApiException a validation error on {@code field} when it is neither */
    static LoginIdentifier parse(String input, String field) {
        if (input != null && input.contains("@")) {
            return EmailAddress.tryParse(input)
                    .map(email -> new LoginIdentifier(OtpDestination.email(email)))
                    .orElseThrow(() -> ApiException.invalidField(field, "field.login_invalid"));
        }
        return PhoneNumber.tryParse(input)
                .map(phone -> new LoginIdentifier(OtpDestination.phone(phone)))
                .orElseThrow(() -> ApiException.invalidField(field, "field.login_invalid"));
    }

    /** The stored phone or email: the key for limits and for the password check. */
    String value() {
        return destination.value();
    }
}
