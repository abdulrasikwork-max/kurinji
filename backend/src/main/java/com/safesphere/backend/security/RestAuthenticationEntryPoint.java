package com.safesphere.backend.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.safesphere.backend.common.response.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

/**
 * Runs when a request needs authentication but has no valid token (missing, malformed, expired,
 * wrong signature). Answers 401 with our standard JSON. The reason is deliberately not revealed.
 */
@Component
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    public RestAuthenticationEntryPoint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        String reason = accountReason(authException);
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, reason == null
                ? "Bearer"
                : "Bearer error=\"invalid_token\", error_description=\"" + reason + "\"");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), ApiError.of(
                HttpStatus.UNAUTHORIZED, "Authentication required or token invalid", request.getRequestURI()));
    }

    /**
     * Phase 10A: the reason ActiveAccountCheck refused an otherwise valid token (RFC 6750 error_description), or null.
     * Only a token this server signed, unexpired, reaches that check, so only the account's own token holder learns it
     * (the phone removes a deleted account's data, or says the password was changed). Expired, forged or missing
     * tokens keep the plain "Bearer" answer.
     */
    static String accountReason(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof JwtValidationException validation) {
                for (OAuth2Error error : validation.getErrors()) {
                    String description = error.getDescription();
                    if (ActiveAccountCheck.ACCOUNT_DELETED.equals(description)
                            || ActiveAccountCheck.ACCOUNT_UNAVAILABLE.equals(description)
                            || ActiveAccountCheck.CREDENTIALS_CHANGED.equals(description)
                            || ActiveAccountCheck.SESSION_REVOKED.equals(description)) {
                        return description;
                    }
                }
            }
            if (t.getCause() == t) break;
        }
        return null;
    }
}
