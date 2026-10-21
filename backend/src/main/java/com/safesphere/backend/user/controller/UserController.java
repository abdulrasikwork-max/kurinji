package com.safesphere.backend.user.controller;

import com.safesphere.backend.common.exception.UnauthorizedException;
import com.safesphere.backend.user.dto.UserResponse;
import com.safesphere.backend.user.service.UserService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    /**
     * GET /api/users/me. SecurityConfig requires a valid JWT for this URL, so by the time this
     * method runs the token is already verified. The user id is read from the token's "sub" claim.
     */
    @GetMapping("/me")
    public UserResponse me(@AuthenticationPrincipal Jwt jwt) {
        return userService.getProfile(parseUserId(jwt));
    }

    private static long parseUserId(Jwt jwt) {
        try {
            return Long.parseLong(jwt.getSubject());
        } catch (NumberFormatException | NullPointerException e) {
            throw new UnauthorizedException("Invalid token subject");
        }
    }
}
