package com.mojud.shared.web;

import java.util.List;
import java.util.Locale;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.i18n.AcceptHeaderLocaleResolver;

/**
 * The app speaks English and Bangla. The request's {@code Accept-Language} picks one; anything
 * else falls back to English. Phase 1b adds the signed-in user's saved language.
 */
@Configuration(proxyBeanMethods = false)
class LocaleConfig {

    static final Locale BANGLA = Locale.forLanguageTag("bn");

    @Bean
    LocaleResolver localeResolver() {
        AcceptHeaderLocaleResolver resolver = new AcceptHeaderLocaleResolver();
        resolver.setSupportedLocales(List.of(Locale.ENGLISH, BANGLA));
        resolver.setDefaultLocale(Locale.ENGLISH);
        return resolver;
    }
}
