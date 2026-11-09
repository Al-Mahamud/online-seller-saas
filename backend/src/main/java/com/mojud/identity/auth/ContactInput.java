package com.mojud.identity.auth;

import com.mojud.identity.otp.OtpDestination;
import com.mojud.shared.email.EmailAddress;
import com.mojud.shared.error.ApiException;
import com.mojud.shared.phone.PhoneNumber;

/**
 * A phone number in any common form, or an email address, as typed. Normalised to the stored form,
 * so it doubles as the destination of a code.
 */
record ContactInput(OtpDestination destination) {

    /** @throws ApiException a validation error on {@code field} when it is neither: an email's message if it has an "@", else a phone's */
    static ContactInput parse(String input, String field) {
        if (input != null && input.contains("@")) {
            return EmailAddress.tryParse(input)
                    .map(email -> new ContactInput(OtpDestination.email(email)))
                    .orElseThrow(() -> ApiException.invalidField(field, "field.email_invalid"));
        }
        // Without an "@" it was meant as a phone number, so the message asks for one.
        return PhoneNumber.tryParse(input)
                .map(phone -> new ContactInput(OtpDestination.phone(phone)))
                .orElseThrow(() -> ApiException.invalidField(field, "field.phone_invalid"));
    }
}
