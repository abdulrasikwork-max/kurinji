package com.safesphere.backend.auth.controller;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * V25: the page the verification e-mail links to (served by the API itself, so the link and the API share one origin
 * and no extra web server is needed). Static files only: the page holds no data and no token; its script reads the
 * token from the URL fragment and posts it to POST /api/auth/verify-email. A strict Content-Security-Policy allows only
 * these three same-origin files, no referrer is ever sent, nothing is cached and the page cannot be framed.
 */
@RestController
public class VerificationPageController {

    static final String CSP = "default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'self'; "
            + "img-src 'none'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'";

    private static final String HTML = read("verification/verify-email.html");
    private static final String SCRIPT = read("verification/verify-email.js");
    private static final String STYLE = read("verification/verify-email.css");

    @GetMapping("/verify-email")
    public ResponseEntity<String> page() {
        return respond(HTML, MediaType.TEXT_HTML);
    }

    @GetMapping("/verify-email.js")
    public ResponseEntity<String> script() {
        return respond(SCRIPT, MediaType.valueOf("text/javascript"));
    }

    @GetMapping("/verify-email.css")
    public ResponseEntity<String> style() {
        return respond(STYLE, MediaType.valueOf("text/css"));
    }

    private static ResponseEntity<String> respond(String body, MediaType type) {
        return ResponseEntity.ok()
                .contentType(new MediaType(type, StandardCharsets.UTF_8))
                .header("Content-Security-Policy", CSP)
                .header("Referrer-Policy", "no-referrer")
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header("X-Content-Type-Options", "nosniff")
                .body(body);
    }

    private static String read(String path) {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Missing verification page resource " + path, e);
        }
    }
}
