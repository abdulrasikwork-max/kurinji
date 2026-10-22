package com.safesphere.backend.common.exception;

import com.safesphere.backend.common.response.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Turns every exception into the same ApiError JSON.
 * Extending ResponseEntityExceptionHandler covers Spring's own web errors (bad JSON, wrong HTTP
 * method, unknown URL...). Raw exception messages and stack traces are never sent to the client.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** Our own deliberate errors (409, 401, 400...). */
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiError> handleApiException(ApiException ex, HttpServletRequest request) {
        return ResponseEntity.status(ex.getStatus())
                .body(ApiError.of(ex.getStatus(), ex.getMessage(), request.getRequestURI()));
    }

    /** Phase 11 C1: a refused refresh says why in WWW-Authenticate (as the per-request account check does). */
    @ExceptionHandler(SessionRefusedException.class)
    public ResponseEntity<ApiError> handleSessionRefused(SessionRefusedException ex, HttpServletRequest request) {
        return ResponseEntity.status(ex.getStatus())
                .header(HttpHeaders.WWW_AUTHENTICATE,
                        "Bearer error=\"invalid_token\", error_description=\"" + ex.getReason() + "\"")
                .body(ApiError.of(ex.getStatus(), ex.getMessage(), request.getRequestURI()));
    }

    /** A database rule (for example the unique email) was broken. */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiError> handleDataIntegrity(HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiError.of(HttpStatus.CONFLICT, "The request conflicts with existing data", request.getRequestURI()));
    }

    /** Two people changed the same record at once (Phase 7: two moderators acting on one case). */
    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<ApiError> handleConcurrentChange(HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiError.of(HttpStatus.CONFLICT, "Someone else changed this at the same time. Please reload.",
                        request.getRequestURI()));
    }

    /** Anything unexpected: log it for developers, tell the client nothing internal. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception on {} {}", request.getMethod(), request.getRequestURI(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiError.of(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred", request.getRequestURI()));
    }

    /** Bean Validation failed (@NotBlank, @Size...). Returns 400 with one message per field. */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        for (FieldError error : ex.getBindingResult().getFieldErrors()) {
            fieldErrors.putIfAbsent(error.getField(), error.getDefaultMessage());
        }
        ApiError body = ApiError.of(HttpStatus.BAD_REQUEST, "Validation failed", pathOf(request), fieldErrors);
        return ResponseEntity.badRequest().body(body);
    }

    /** All other Spring MVC errors (400 malformed JSON, 404, 405, 415...) share the same body. */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex, @Nullable Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        HttpStatus status = HttpStatus.valueOf(statusCode.value());
        ApiError error = ApiError.of(status, genericMessage(status), pathOf(request));
        return ResponseEntity.status(status).headers(headers).body(error);
    }

    private static String genericMessage(HttpStatus status) {
        return switch (status) {
            case BAD_REQUEST -> "Malformed or invalid request";
            case NOT_FOUND -> "Resource not found";
            case METHOD_NOT_ALLOWED -> "HTTP method not allowed";
            case UNSUPPORTED_MEDIA_TYPE -> "Unsupported content type";
            default -> status.getReasonPhrase();
        };
    }

    private static String pathOf(WebRequest request) {
        return request instanceof ServletWebRequest servletRequest
                ? servletRequest.getRequest().getRequestURI()
                : "";
    }
}
