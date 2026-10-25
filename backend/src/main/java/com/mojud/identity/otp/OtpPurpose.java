package com.mojud.identity.otp;

/** What a code may be used for. A code issued for one purpose never works for another. */
public enum OtpPurpose {
    /** Sent only to a phone or email that has no account yet. */
    SIGNUP,
    /** Sign-in without a password; sent only to a phone or email that has an account. */
    LOGIN
}
