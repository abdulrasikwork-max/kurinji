package com.safesphere.backend.community.controller;

import com.safesphere.backend.common.exception.InvalidRequestException;
import com.safesphere.backend.community.dto.CommunityDtos.MeView;
import com.safesphere.backend.community.dto.CommunityDtos.Programme;
import com.safesphere.backend.community.dto.CommunityDtos.StartRequest;
import com.safesphere.backend.community.service.VerificationService;
import com.safesphere.backend.security.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Phase N3: the signed-in person's own membership and verification request. Any signed-in, adult account (membership is
 * what these endpoints are for). Caller identity only from the JWT.
 */
@RestController
@RequestMapping("/api/community")
public class CommunityController {

    /** A little above the 5 MB photo limit, so the sanitizer gives its own clear message for 5 MB + a few bytes. */
    static final int MAX_UPLOAD_BYTES = 5 * 1024 * 1024 + 1;

    private final VerificationService verification;

    public CommunityController(VerificationService verification) {
        this.verification = verification;
    }

    @GetMapping("/me")
    public MeView me(@AuthenticationPrincipal Jwt jwt) {
        return verification.me(CurrentUser.id(jwt));
    }

    @GetMapping("/programmes")
    public List<Programme> programmes() {
        return verification.programmes();
    }

    @PostMapping("/verification")
    public MeView start(@AuthenticationPrincipal Jwt jwt, @RequestBody StartRequest request) {
        return verification.start(CurrentUser.id(jwt), request);
    }

    @PostMapping("/verification/code")
    public MeView newCode(@AuthenticationPrincipal Jwt jwt) {
        return verification.newCode(CurrentUser.id(jwt));
    }

    @PutMapping(path = "/verification/photo", consumes = {MediaType.IMAGE_JPEG_VALUE, MediaType.IMAGE_PNG_VALUE})
    public MeView uploadPhoto(@AuthenticationPrincipal Jwt jwt, HttpServletRequest request) throws IOException {
        return verification.uploadPhoto(CurrentUser.id(jwt), readLimited(request.getInputStream()));
    }

    @DeleteMapping("/verification")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void withdraw(@AuthenticationPrincipal Jwt jwt) {
        verification.withdraw(CurrentUser.id(jwt));
    }

    /** Reads at most [MAX_UPLOAD_BYTES]; a longer body is refused without reading the rest into memory. */
    static byte[] readLimited(InputStream in) throws IOException {
        byte[] bytes = in.readNBytes(MAX_UPLOAD_BYTES + 1);
        if (bytes.length > MAX_UPLOAD_BYTES) throw new InvalidRequestException("A photo can be at most 5 MB");
        return bytes;
    }
}
