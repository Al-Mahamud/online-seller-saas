package com.mojud.identity.auth;

import com.mojud.identity.AuthenticatedUser;
import com.mojud.shared.error.ApiException;
import com.mojud.shared.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.NegatedRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.security.web.authentication.logout.HttpStatusReturningLogoutSuccessHandler;
import org.springframework.security.web.header.writers.DelegatingRequestMatcherHeaderWriter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import org.springframework.security.web.header.writers.StaticHeadersWriter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * Browser sessions in an HttpOnly cookie, and CSRF protection for every state-changing request
 * (backend-plan.md §11). The web app calls {@code GET /api/v1/auth/csrf} once, then echoes the
 * {@code XSRF-TOKEN} cookie in the {@code X-XSRF-TOKEN} header.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({IdentityProperties.class, WebProperties.class})
class SecurityConfig {

    /** The app needs no device features; nothing embedded may ask for them either. */
    static final String PERMISSIONS_POLICY =
            "camera=(), microphone=(), geolocation=(), payment=(), usb=(), interest-cohort=()";

    static final String API_CSP = "default-src 'none'; frame-ancestors 'none'; sandbox";

    /** Swagger UI's files, served by springdoc under {@code /api/swagger-ui/} when API docs are on. */
    private static final RequestMatcher DOCS_PAGE = PathPatternRequestMatcher.withDefaults().matcher("/api/swagger-ui/**");

    /** Swagger UI sets styles inline and shows the schema's data: images. */
    static final String DOCS_PAGE_CSP =
            "default-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; frame-ancestors 'none'";

    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http,
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver errors) throws Exception {
        http
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers("/healthz/**", "/error", "/api/docs/**", "/api/swagger-ui/**",
                                "/api/v1/openapi/**").permitAll()
                        .requestMatchers("/api/v1/auth/**").permitAll()
                        // Endpoints anyone may call, such as looking up an invite from its link.
                        // Each module puts its open endpoints here rather than adding rules above.
                        .requestMatchers("/api/v1/public/**").permitAll()
                        .requestMatchers("/api/v1/admin/**").hasRole("PLATFORM_ADMIN")
                        .anyRequest().authenticated())
                // Spring's defaults (nosniff, frame DENY, no-store, HSTS on HTTPS) plus: API answers
                // are data, never a page, so they may load nothing. The API docs page (local runs
                // only) loads its own scripts and styles.
                .headers(headers -> headers
                        .referrerPolicy(referrer -> referrer.policy(ReferrerPolicy.NO_REFERRER))
                        .addHeaderWriter(new StaticHeadersWriter("Permissions-Policy", PERMISSIONS_POLICY))
                        .addHeaderWriter(new DelegatingRequestMatcherHeaderWriter(new NegatedRequestMatcher(DOCS_PAGE),
                                new StaticHeadersWriter("Content-Security-Policy", API_CSP)))
                        .addHeaderWriter(new DelegatingRequestMatcherHeaderWriter(DOCS_PAGE,
                                new StaticHeadersWriter("Content-Security-Policy", DOCS_PAGE_CSP))))
                // Payment gateways call /api/v1/public/gateway/** with no session or token; those
                // callbacks are verified with the gateway itself instead.
                .csrf(csrf -> csrf.spa().ignoringRequestMatchers("/api/v1/public/gateway/**"))
                .cors(cors -> {
                })
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                // An API never redirects to "the page you wanted" after login.
                .requestCache(AbstractHttpConfigurer::disable)
                .logout(logout -> logout
                        .logoutUrl("/api/v1/auth/logout")
                        .logoutSuccessHandler(new HttpStatusReturningLogoutSuccessHandler(HttpStatus.NO_CONTENT)))
                // Hand security errors to ApiExceptionHandler, so they use the same problem format.
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint((request, response, e) -> errors.resolveException(request, response, null, e))
                        .accessDeniedHandler((request, response, e) -> errors.resolveException(request, response, null,
                                adminWithoutTwoFactor(request) ? new ApiException(ErrorCode.ADMIN_TWO_FACTOR_REQUIRED) : e)));
        return http.build();
    }

    /** A platform admin whose session did not pass two-step sign-in, asking for the admin area. */
    private static boolean adminWithoutTwoFactor(HttpServletRequest request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return request.getRequestURI().startsWith("/api/v1/admin/") && authentication != null
                && authentication.getPrincipal() instanceof AuthenticatedUser user && user.platformAdmin() && !user.twoFactor();
    }

    /** Cross-origin requests only from the configured origins; none by default. */
    @Bean
    CorsConfigurationSource corsConfigurationSource(WebProperties web) {
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        if (!web.allowedOrigins().isEmpty()) {
            CorsConfiguration cors = new CorsConfiguration();
            cors.setAllowedOrigins(web.allowedOrigins());
            cors.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE"));
            cors.setAllowedHeaders(List.of("Content-Type", "X-XSRF-TOKEN", "Idempotency-Key", "Accept-Language", "X-Business-Id"));
            cors.setAllowCredentials(true);
            source.registerCorsConfiguration("/api/**", cors);
        }
        return source;
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        // Argon2id. The {argon2} prefix lets the algorithm change later without invalidating
        // stored hashes.
        return new DelegatingPasswordEncoder("argon2",
                Map.of("argon2", Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8()));
    }

    @Bean
    AuthenticationManager authenticationManager(UserDetailsService accounts, PasswordEncoder passwordEncoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(accounts);
        provider.setPasswordEncoder(passwordEncoder);
        return new ProviderManager(provider);
    }
}
