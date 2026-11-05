package com.mojud.shared.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Refuses to start a server (profile {@code prod}) whose settings are missing or unsafe, naming
 * every problem at once (implementation-plan.md 13b): better than an app that starts and then
 * cannot store a secret, or writes sign-in codes to its log. Settings a server can run without
 * for now (email, SSLCommerz, Sentry) are logged as warnings.
 */
@Component
@Profile("prod")
class ProductionChecks {

    private static final Logger log = LoggerFactory.getLogger(ProductionChecks.class);

    /** SslCommerzClient's address when none is set. */
    private static final String SANDBOX = "https://sandbox.sslcommerz.com";

    ProductionChecks(Environment environment) {
        List<String> problems = problems(environment);
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Unsafe production settings:\n- " + String.join("\n- ", problems));
        }
        warnings(environment).forEach(log::warn);
    }

    static List<String> problems(Environment environment) {
        List<String> problems = new ArrayList<>();
        required(environment, problems, "spring.datasource.password", "DB_APP_PASSWORD");
        required(environment, problems, "spring.flyway.password", "DB_OWNER_PASSWORD");
        required(environment, problems, "mojud.db.system.password", "DB_SYSTEM_PASSWORD");
        required(environment, problems, "mojud.security.encryption-key", "ENCRYPTION_KEY");
        for (String[] url : new String[][] {{"mojud.web.base-url", "WEB_BASE_URL"}, {"mojud.web.api-base-url", "API_BASE_URL"}}) {
            String value = environment.getProperty(url[0], "");
            if (!value.startsWith("https://")) {
                problems.add(url[1] + " must be an https:// address (it is \"" + value + "\")");
            }
        }
        if ("false".equals(environment.getProperty("server.servlet.session.cookie.secure"))) {
            problems.add("The session cookie must be Secure (server.servlet.session.cookie.secure)");
        }
        for (String[] channel : new String[][] {{"mojud.sms.provider", "SMS_PROVIDER"}, {"mojud.email.provider", "EMAIL_PROVIDER"}}) {
            if ("log".equals(environment.getProperty(channel[0], "").toLowerCase(Locale.ROOT))) {
                problems.add(channel[1] + "=log would write sign-in codes to the log");
            }
        }
        if ("false".equals(environment.getProperty("mojud.admin.require-two-factor"))) {
            problems.add("mojud.admin.require-two-factor=false would open the admin area without two-step sign-in");
        }
        return problems;
    }

    static List<String> warnings(Environment environment) {
        List<String> warnings = new ArrayList<>();
        if ("none".equals(environment.getProperty("mojud.email.provider", "none"))
                && "none".equals(environment.getProperty("mojud.sms.provider", "none"))) {
            warnings.add("No email or SMS provider (EMAIL_PROVIDER): sign-up and sign-in codes cannot be sent");
        }
        if (blank(environment, "mojud.billing.sslcommerz.store-id")) {
            warnings.add("No SSLCommerz store (SSLCOMMERZ_STORE_ID): plans can only be paid by bank transfer");
        } else if (environment.getProperty("mojud.billing.sslcommerz.base-url", SANDBOX).contains("sandbox")) {
            warnings.add("SSLCOMMERZ_BASE_URL is the sandbox: card and bKash payments are tests, and no money arrives");
        }
        if (blank(environment, "sentry.dsn")) {
            warnings.add("No SENTRY_DSN: errors are only in the log");
        }
        if ("local".equals(environment.getProperty("mojud.storage.provider"))) {
            warnings.add("STORAGE_PROVIDER=local: uploaded images are on this server's disk, not in the backups");
        }
        return warnings;
    }

    private static void required(Environment environment, List<String> problems, String key, String variable) {
        if (blank(environment, key)) {
            problems.add(variable + " is not set");
        }
    }

    private static boolean blank(Environment environment, String key) {
        String value = environment.getProperty(key);
        return value == null || value.isBlank();
    }
}
