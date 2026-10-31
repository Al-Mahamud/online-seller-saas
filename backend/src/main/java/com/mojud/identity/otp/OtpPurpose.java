package com.mojud.identity.otp;

/** What a code may be used for. A code issued for one purpose never works for another. */
public enum OtpPurpose {
    /** Sent only to a phone or email no account has verified. */
    SIGNUP,
    /** Finding a forgotten User ID; sent only to a phone or email an account has verified. */
    USER_ID_RECOVERY,
    /**
     * Choosing a new password; requested with the User ID through {@code /api/v1/auth/password/code}
     * and sent to that account's verified phone or email.
     */
    PASSWORD_RESET,
    /**
     * Proving a phone or email for the signed-in user's account; requested only through
     * {@code /api/v1/me/contact/code}, never before sign-in.
     */
    VERIFY_CONTACT
}
