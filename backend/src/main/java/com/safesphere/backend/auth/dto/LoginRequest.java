package com.safesphere.backend.auth.dto;

import jakarta.validation.constraints.NotBlank;

/** Body of POST /api/auth/login. Only presence is checked; a wrong value is simply "invalid credentials". */
public record LoginRequest(

        @NotBlank(message = "Email is required")
        String email,

        @NotBlank(message = "Password is required")
        String password
) {
}
