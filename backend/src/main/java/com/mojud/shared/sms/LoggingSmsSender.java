package com.mojud.shared.sms;

import com.mojud.shared.phone.PhoneNumber;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Writes messages to the log instead of sending them. Local runs and tests only: codes appear in the log. */
@Component
@ConditionalOnProperty(name = "mojud.sms.provider", havingValue = "log")
class LoggingSmsSender implements SmsSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingSmsSender.class);

    @Override
    public void send(PhoneNumber to, String text) {
        log.info("SMS to {}: {}", to, text);
    }
}
