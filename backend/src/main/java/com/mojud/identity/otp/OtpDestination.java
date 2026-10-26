package com.mojud.identity.otp;

import com.mojud.shared.email.EmailAddress;
import com.mojud.shared.phone.PhoneNumber;

/** Where a code goes: an E.164 phone number or a lower-case email address. */
public record OtpDestination(OtpChannel channel, String value) {

    public static OtpDestination phone(PhoneNumber phone) {
        return new OtpDestination(OtpChannel.PHONE, phone.e164());
    }

    /** From the stored form: an E.164 phone starts with {@code +}, anything else is an email. */
    public static OtpDestination ofStored(String value) {
        return new OtpDestination(value.startsWith("+") ? OtpChannel.PHONE : OtpChannel.EMAIL, value);
    }

    public static OtpDestination email(EmailAddress email) {
        return new OtpDestination(OtpChannel.EMAIL, email.value());
    }

    public boolean isPhone() {
        return channel == OtpChannel.PHONE;
    }
}
