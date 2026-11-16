package com.safesphere.backend.social.controller;

import com.safesphere.backend.security.CurrentUser;
import com.safesphere.backend.social.dto.MomentDtos.QuestionAdminView;
import com.safesphere.backend.social.dto.MomentDtos.QuestionRequest;
import com.safesphere.backend.social.service.DailyQuestionService;
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

/** Phase N4 (N-49): the daily question, for admins (ADMIN only: SecurityConfig /api/admin/** and the service again). */
@RestController
@RequestMapping("/api/admin/questions")
public class DailyQuestionAdminController {

    private final DailyQuestionService questions;

    public DailyQuestionAdminController(DailyQuestionService questions) {
        this.questions = questions;
    }

    @GetMapping
    public List<QuestionAdminView> list(@AuthenticationPrincipal Jwt jwt) {
        return questions.list(CurrentUser.id(jwt));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public QuestionAdminView create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody QuestionRequest request) {
        return questions.create(CurrentUser.id(jwt), request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        questions.delete(CurrentUser.id(jwt), id);
    }
}
