package com.safesphere.backend.social.controller;

import com.safesphere.backend.security.CurrentUser;
import com.safesphere.backend.social.dto.ConversationDtos.ConversationView;
import com.safesphere.backend.social.dto.ConversationDtos.MessageView;
import com.safesphere.backend.social.dto.ConversationDtos.Settings;
import com.safesphere.backend.social.dto.ConversationDtos.TextRequest;
import com.safesphere.backend.social.service.ConversationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Phase N5 (API.md "Phase N5"): private replies, plans, and who may reply privately. */
@RestController
public class ConversationController {

    private final ConversationService conversations;

    public ConversationController(ConversationService conversations) {
        this.conversations = conversations;
    }

    @PostMapping("/api/moments/{id}/private-replies")
    @ResponseStatus(HttpStatus.CREATED)
    public ConversationView startReply(@AuthenticationPrincipal Jwt jwt, @PathVariable String id,
                                       @Valid @RequestBody TextRequest request) {
        return conversations.startReply(CurrentUser.id(jwt), id, request.text());
    }

    @GetMapping("/api/conversations/{id}")
    public ConversationView get(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        return conversations.get(CurrentUser.id(jwt), id);
    }

    @PostMapping("/api/conversations/{id}/messages")
    @ResponseStatus(HttpStatus.CREATED)
    public MessageView send(@AuthenticationPrincipal Jwt jwt, @PathVariable String id,
                            @Valid @RequestBody TextRequest request) {
        return conversations.send(CurrentUser.id(jwt), id, request.text());
    }

    @PostMapping("/api/conversations/{id}/end")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void end(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        conversations.end(CurrentUser.id(jwt), id);
    }

    @GetMapping("/api/social-settings")
    public Settings settings(@AuthenticationPrincipal Jwt jwt) {
        return new Settings(conversations.privateReplies(CurrentUser.id(jwt)).name());
    }

    @PutMapping("/api/social-settings")
    public Settings setSettings(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody Settings request) {
        return new Settings(conversations.setPrivateReplies(CurrentUser.id(jwt), request.privateReplies()).name());
    }
}
