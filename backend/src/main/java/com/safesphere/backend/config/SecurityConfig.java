package com.safesphere.backend.config;

import com.safesphere.backend.security.RestAccessDeniedHandler;
import com.safesphere.backend.security.RestAuthenticationEntryPoint;
import com.safesphere.backend.security.RoleAuthorization;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * The single place that decides who may call what.
 *
 *   POST /api/auth/refresh       public; the refresh secret in the body is the credential (Phase 11 C1)
 *   POST /api/auth/sign-out      public; ends the device session whose refresh secret is in the body
 *   POST /api/auth/phone/start   public; a phone number (Phase N2: the only way in; limited per address and number)
 *   POST /api/auth/phone/verify|resend|change  public; the challenge token is the credential (limited)
 *   POST /api/auth/sign-up     public; the one-use sign-up ticket is the credential (N2; limited per address)
 *   GET  /api/moderation/access  any valid JWT (tells the app whether to show moderation at all)
 *   /api/moderation/**           valid JWT + MODERATOR or ADMIN role read from the database (Phase 7)
 *   /api/admin/**                valid JWT + ADMIN role read from the database (Phase 7)
 *   everything else              needs a valid JWT ("Authorization: Bearer ...")
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            RestAuthenticationEntryPoint authenticationEntryPoint,
            RestAccessDeniedHandler accessDeniedHandler,
            RoleAuthorization roles) throws Exception {

        http
                // CSRF protects browser cookie sessions. We use no cookies, only a header token.
                .csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                // Stateless: the server remembers nothing between requests; the JWT is the proof.
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth
                        // Phase 11 C1: refresh and sign-out carry their own credential (the refresh secret).
                        .requestMatchers(HttpMethod.POST, "/api/auth/refresh", "/api/auth/sign-out").permitAll()
                        // Phase N2: the only way in. start is open to anyone (limited in memory); the challenge token
                        // (verify, resend, change) or the sign-up ticket (sign-up) is the credential.
                        .requestMatchers(HttpMethod.POST, "/api/auth/phone/start", "/api/auth/phone/verify",
                                "/api/auth/phone/resend", "/api/auth/phone/change", "/api/auth/sign-up").permitAll()
                        .requestMatchers("/error").permitAll()
                        // Phase D12: the uptime monitor and the deploy smoke test; GET only, says only UP or DOWN.
                        .requestMatchers(HttpMethod.GET, "/api/health").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/moderation/access").authenticated()
                        .requestMatchers("/api/moderation/**").access(roles.moderator())
                        .requestMatchers("/api/admin/**").access(roles.admin())
                        .anyRequest().authenticated())
                // Reads "Authorization: Bearer <jwt>", verifies it with the JwtDecoder bean.
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(Customizer.withDefaults())
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler));

        return http.build();
    }

    /** BCrypt: slow, salted, one-way hashing. The salt is stored inside the hash string. */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * Native Android apps do not use CORS (it is a browser rule). With the default empty list no
     * browser origin is allowed. Add origins in CORS_ALLOWED_ORIGINS only if a web client appears.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource(
            @Value("${app.cors.allowed-origins:}") List<String> allowedOrigins) {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(allowedOrigins);
        configuration.setAllowedMethods(List.of("GET", "POST", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type"));

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", configuration);
        return source;
    }
}
