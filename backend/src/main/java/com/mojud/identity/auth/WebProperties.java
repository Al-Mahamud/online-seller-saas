package com.mojud.identity.auth;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code mojud.web.*}. The web app is normally served from the same origin as the API, which needs
 * no CORS at all; list other origins here only for setups that need them.
 */
@ConfigurationProperties("mojud.web")
record WebProperties(@DefaultValue List<String> allowedOrigins) {
}
