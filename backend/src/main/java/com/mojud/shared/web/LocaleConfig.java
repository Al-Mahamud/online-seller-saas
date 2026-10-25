package com.mojud.shared.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.util.List;
import java.util.Locale;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.i18n.AcceptHeaderLocaleResolver;

/**
 * The app speaks English and Bangla. A signed-in user gets the language saved on their account
 * (kept in the session under {@link #SESSION_LANGUAGE} by the identity module); anyone else gets
 * what the request's {@code Accept-Language} asks for, falling back to English.
 */
@Configuration(proxyBeanMethods = false)
public class LocaleConfig {

    /** Session attribute holding the signed-in user's language tag: {@code "bn"} or {@code "en"}. */
    public static final String SESSION_LANGUAGE = "mojud.language";

    static final Locale BANGLA = Locale.forLanguageTag("bn");
    private static final List<Locale> SUPPORTED = List.of(Locale.ENGLISH, BANGLA);

    @Bean
    LocaleResolver localeResolver() {
        AcceptHeaderLocaleResolver fromHeader = new AcceptHeaderLocaleResolver();
        fromHeader.setSupportedLocales(SUPPORTED);
        fromHeader.setDefaultLocale(Locale.ENGLISH);
        return new LocaleResolver() {
            @Override
            public Locale resolveLocale(HttpServletRequest request) {
                HttpSession session = request.getSession(false);
                Object saved = session == null ? null : session.getAttribute(SESSION_LANGUAGE);
                if (saved instanceof String tag) {
                    Locale locale = Locale.forLanguageTag(tag);
                    if (SUPPORTED.contains(locale)) {
                        return locale;
                    }
                }
                return fromHeader.resolveLocale(request);
            }

            @Override
            public void setLocale(HttpServletRequest request, HttpServletResponse response, Locale locale) {
                throw new UnsupportedOperationException("The language is changed with PATCH /api/v1/me");
            }
        };
    }
}
