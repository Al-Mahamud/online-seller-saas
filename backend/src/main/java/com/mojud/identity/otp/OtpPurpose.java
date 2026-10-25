package com.mojud.identity.otp;

/** What a code may be used for. A code issued for one purpose never works for another. */
public enum OtpPurpose {
    /** Sent only to a phone or email no account has verified. */
    SIGNUP,
    /** Sign-in without a password; sent only to a phone or email an account has verified. */
    LOGIN,
    /** Choosing a new password; sent only to a phone or email an account has verified. */
    PASSWORD_RESET,
    /**
     * Proving a phone or email for the signed-in user's account; requested only through
     * {@code /api/v1/me/contact/code}, never before sign-in.
     */
    VERIFY_CONTACT
}
