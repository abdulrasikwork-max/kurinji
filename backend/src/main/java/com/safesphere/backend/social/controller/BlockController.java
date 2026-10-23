package com.safesphere.backend.social.controller;

import com.safesphere.backend.security.CurrentUser;
import com.safesphere.backend.social.dto.BlockDtos.PersonTarget;
import com.safesphere.backend.social.dto.ProfileDtos.PersonSummary;
import com.safesphere.backend.social.service.BlockService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Phase 13A: blocks and mutes. Private lists of their owner; the other person is never told. */
@RestController
@RequestMapping("/api")
public class BlockController {

    private final BlockService blocks;

    public BlockController(BlockService blocks) {
        this.blocks = blocks;
    }

    @PostMapping("/blocks")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void block(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody PersonTarget target) {
        blocks.block(CurrentUser.id(jwt), target.profileId());
    }

    @GetMapping("/blocks")
    public List<PersonSummary> blocked(@AuthenticationPrincipal Jwt jwt) {
        return blocks.blocked(CurrentUser.id(jwt));
    }

    @DeleteMapping("/blocks/{profileId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unblock(@AuthenticationPrincipal Jwt jwt, @PathVariable String profileId) {
        blocks.unblock(CurrentUser.id(jwt), profileId);
    }

    @PostMapping("/mutes")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void mute(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody PersonTarget target) {
        blocks.mute(CurrentUser.id(jwt), target.profileId());
    }

    @GetMapping("/mutes")
    public List<PersonSummary> muted(@AuthenticationPrincipal Jwt jwt) {
        return blocks.muted(CurrentUser.id(jwt));
    }

    @DeleteMapping("/mutes/{profileId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unmute(@AuthenticationPrincipal Jwt jwt, @PathVariable String profileId) {
        blocks.unmute(CurrentUser.id(jwt), profileId);
    }
}
