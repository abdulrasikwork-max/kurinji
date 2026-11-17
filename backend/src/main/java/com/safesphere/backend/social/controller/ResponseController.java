package com.safesphere.backend.social.controller;

import com.safesphere.backend.security.CurrentUser;
import com.safesphere.backend.social.dto.MomentDtos.MomentResponse;
import com.safesphere.backend.social.dto.MomentDtos.MoreRequest;
import com.safesphere.backend.social.dto.MomentDtos.People;
import com.safesphere.backend.social.entity.ResponseEnums.SignalKind;
import com.safesphere.backend.social.service.ResponseService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Phase N5 (docs/PHASE_N5.md, API.md "Phase N5"): Same, Say more, the follow-up, I'm in, the author's answers. */
@RestController
public class ResponseController {

    private final ResponseService responses;

    public ResponseController(ResponseService responses) {
        this.responses = responses;
    }

    @PutMapping("/api/moments/{id}/same")
    public MomentResponse same(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        return responses.signal(CurrentUser.id(jwt), id, SignalKind.SAME, true);
    }

    @DeleteMapping("/api/moments/{id}/same")
    public MomentResponse notSame(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        return responses.signal(CurrentUser.id(jwt), id, SignalKind.SAME, false);
    }

    @PutMapping("/api/moments/{id}/say-more")
    public MomentResponse sayMore(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        return responses.signal(CurrentUser.id(jwt), id, SignalKind.SAY_MORE, true);
    }

    @DeleteMapping("/api/moments/{id}/say-more")
    public MomentResponse notSayMore(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        return responses.signal(CurrentUser.id(jwt), id, SignalKind.SAY_MORE, false);
    }

    @PostMapping("/api/moments/{id}/more")
    @ResponseStatus(HttpStatus.CREATED)
    public MomentResponse more(@AuthenticationPrincipal Jwt jwt, @PathVariable String id,
                               @Valid @RequestBody MoreRequest request) {
        return responses.more(CurrentUser.id(jwt), id, request.text());
    }

    @PutMapping("/api/moments/{id}/in")
    public MomentResponse ask(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        return responses.ask(CurrentUser.id(jwt), id);
    }

    @DeleteMapping("/api/moments/{id}/in")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void withdraw(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        responses.withdraw(CurrentUser.id(jwt), id);
    }

    @GetMapping("/api/moments/{id}/people")
    public People people(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        return responses.people(CurrentUser.id(jwt), id);
    }

    @PostMapping("/api/moments/{id}/people/{askId}/add")
    public People add(@AuthenticationPrincipal Jwt jwt, @PathVariable String id, @PathVariable String askId) {
        return responses.add(CurrentUser.id(jwt), id, askId);
    }

    @PostMapping("/api/moments/{id}/people/{askId}/set-aside")
    public People setAside(@AuthenticationPrincipal Jwt jwt, @PathVariable String id, @PathVariable String askId) {
        return responses.setAside(CurrentUser.id(jwt), id, askId, true);
    }

    @DeleteMapping("/api/moments/{id}/people/{askId}/set-aside")
    public People bringBack(@AuthenticationPrincipal Jwt jwt, @PathVariable String id, @PathVariable String askId) {
        return responses.setAside(CurrentUser.id(jwt), id, askId, false);
    }

    @DeleteMapping("/api/moments/{id}/people/{askId}")
    public People remove(@AuthenticationPrincipal Jwt jwt, @PathVariable String id, @PathVariable String askId) {
        return responses.remove(CurrentUser.id(jwt), id, askId);
    }

    @PostMapping("/api/moments/{id}/call-off")
    public MomentResponse callOff(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        return responses.callOff(CurrentUser.id(jwt), id);
    }
}
