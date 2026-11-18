package com.safesphere.backend.push.controller;

import com.safesphere.backend.push.dto.PushDtos.DeviceRequest;
import com.safesphere.backend.push.dto.PushDtos.Settings;
import com.safesphere.backend.push.service.PushDeviceService;
import com.safesphere.backend.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * N7 (D28, D31): this phone's push token (for the CURRENT sign-in only) and the person's notification switches. JWT
 * required; never part of the community gate (safety pushes work without a membership).
 */
@RestController
public class PushController {

    private final PushDeviceService service;

    public PushController(PushDeviceService service) {
        this.service = service;
    }

    @PutMapping("/api/push/device")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void register(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody DeviceRequest request) {
        service.register(CurrentUser.id(jwt), CurrentUser.sessionId(jwt), request.token(), request.appVersion());
    }

    @DeleteMapping("/api/push/device")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unregister(@AuthenticationPrincipal Jwt jwt) {
        service.unregister(CurrentUser.id(jwt), CurrentUser.sessionId(jwt));
    }

    @GetMapping("/api/push/settings")
    public Settings settings(@AuthenticationPrincipal Jwt jwt) {
        return service.settings(CurrentUser.id(jwt));
    }

    @PutMapping("/api/push/settings")
    public Settings change(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody Settings settings) {
        return service.change(CurrentUser.id(jwt), settings);
    }
}
