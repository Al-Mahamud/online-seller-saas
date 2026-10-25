package com.mojud.shared.sms;

import com.mojud.shared.phone.PhoneNumber;

/**
 * Sends a text message. Until a Bangladesh SMS gateway is chosen (backend-plan.md §13), only the
 * logging sender exists, and only where {@code mojud.sms.provider=log}; without any sender,
 * one-time codes go by email only.
 */
public interface SmsSender {

    void send(PhoneNumber to, String text);
}
