package com.safesphere.backend.social.controller;

import com.safesphere.backend.common.exception.InvalidRequestException;
import com.safesphere.backend.security.CurrentUser;
import com.safesphere.backend.social.dto.ConnectionDtos.ConnectRequest;
import com.safesphere.backend.social.dto.ConnectionDtos.ConnectResult;
import com.safesphere.backend.social.dto.ConnectionDtos.InviteCreated;
import com.safesphere.backend.social.dto.ConnectionDtos.InviteItem;
import com.safesphere.backend.social.dto.ConnectionDtos.RedeemRequest;
import com.safesphere.backend.social.dto.ConnectionDtos.RequestItem;
import com.safesphere.backend.social.dto.ProfileDtos.PersonSummary;
import com.safesphere.backend.social.service.ConnectionService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Phase 13A: connection requests, connections and invite codes. Identity only from the JWT. */
@RestController
@RequestMapping("/api/connections")
public class ConnectionController {

    private final ConnectionService connections;

    public ConnectionController(ConnectionService connections) {
        this.connections = connections;
    }

    @PostMapping("/requests")
    public ResponseEntity<ConnectResult> request(@AuthenticationPrincipal Jwt jwt,
                                                 @Valid @RequestBody ConnectRequest request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(connections.request(CurrentUser.id(jwt), request.profileId()));
    }

    @GetMapping("/requests")
    public List<RequestItem> requests(@AuthenticationPrincipal Jwt jwt, @RequestParam String direction) {
        long userId = CurrentUser.id(jwt);
        return switch (direction) {
            case "incoming" -> connections.incoming(userId);
            case "outgoing" -> connections.outgoing(userId);
            default -> throw new InvalidRequestException("direction must be incoming or outgoing");
        };
    }

    @PostMapping("/requests/{requestId}/accept")
    public ConnectResult accept(@AuthenticationPrincipal Jwt jwt, @PathVariable String requestId) {
        return connections.accept(CurrentUser.id(jwt), requestId);
    }

    @PostMapping("/requests/{requestId}/decline")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void decline(@AuthenticationPrincipal Jwt jwt, @PathVariable String requestId) {
        connections.decline(CurrentUser.id(jwt), requestId);
    }

    @DeleteMapping("/requests/{requestId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable String requestId) {
        connections.cancel(CurrentUser.id(jwt), requestId);
    }

    @GetMapping
    public List<PersonSummary> list(@AuthenticationPrincipal Jwt jwt) {
        return connections.list(CurrentUser.id(jwt));
    }

    @DeleteMapping("/{profileId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@AuthenticationPrincipal Jwt jwt, @PathVariable String profileId) {
        connections.remove(CurrentUser.id(jwt), profileId);
    }

    @PostMapping("/invites")
    @ResponseStatus(HttpStatus.CREATED)
    public InviteCreated createInvite(@AuthenticationPrincipal Jwt jwt) {
        return connections.createInvite(CurrentUser.id(jwt));
    }

    @GetMapping("/invites")
    public List<InviteItem> invites(@AuthenticationPrincipal Jwt jwt) {
        return connections.activeInvites(CurrentUser.id(jwt));
    }

    @DeleteMapping("/invites/{inviteId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revokeInvite(@AuthenticationPrincipal Jwt jwt, @PathVariable String inviteId) {
        connections.revokeInvite(CurrentUser.id(jwt), inviteId);
    }

    @PostMapping("/invites/redeem")
    public ConnectResult redeem(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody RedeemRequest request) {
        return connections.redeem(CurrentUser.id(jwt), request.code());
    }
}
