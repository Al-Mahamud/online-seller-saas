package com.mojud.shared.email;

/** One email, ready to send: the same content as plain text and as HTML ({@link EmailComposer}). */
public record EmailMessage(String subject, String text, String html) {
}
