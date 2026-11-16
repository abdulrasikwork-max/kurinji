package com.safesphere.backend.social.service;

import com.safesphere.backend.common.exception.ConflictException;
import com.safesphere.backend.common.exception.ForbiddenException;
import com.safesphere.backend.common.exception.InvalidRequestException;
import com.safesphere.backend.common.exception.ResourceNotFoundException;
import com.safesphere.backend.config.MomentProperties;
import com.safesphere.backend.social.dto.MomentDtos.QuestionAdminView;
import com.safesphere.backend.social.dto.MomentDtos.QuestionRequest;
import com.safesphere.backend.social.entity.DailyQuestion;
import com.safesphere.backend.social.policy.MomentPolicy;
import com.safesphere.backend.social.policy.MomentTimes;
import com.safesphere.backend.social.repository.DailyQuestionRepository;
import com.safesphere.backend.social.repository.MomentRepository;
import com.safesphere.backend.user.entity.UserRole;
import com.safesphere.backend.user.repository.UserRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Phase N4 (N-49): the daily question, written and scheduled by admins: one per India calendar day for the admin's own
 * community, pinned on Today that day. There is no editing: a question is deleted and written again (its answers stay
 * as ordinary moments). Nothing is counted or ranked. SecurityConfig already limits /api/admin/** to ADMIN; the role is
 * checked here again, and the admin must be an active member (a question belongs to a community's wall).
 */
@Service
public class DailyQuestionService {

    static final int TEXT_MIN = 3;
    static final int TEXT_MAX = 140;
    static final int LIST_DAYS_BACK = 7;
    static final String NOT_FOUND = "Question not found";
    static final String DAY_TAKEN = "That day already has a question. Delete it first to write another.";

    private final DailyQuestionRepository questions;
    private final MomentRepository moments;
    private final UserRepository users;
    private final SocialAccess access;
    private final MomentProperties properties;
    private final Clock clock;

    public DailyQuestionService(DailyQuestionRepository questions, MomentRepository moments, UserRepository users,
                                SocialAccess access, MomentProperties properties, Clock clock) {
        this.questions = questions;
        this.moments = moments;
        this.users = users;
        this.access = access;
        this.properties = properties;
        this.clock = clock;
    }

    /** The community's questions from a week ago onwards (recent, today's, scheduled), oldest first. */
    @Transactional(readOnly = true)
    public List<QuestionAdminView> list(long adminId) {
        long community = requireAdminMember(adminId);
        LocalDate from = MomentTimes.indiaDay(now()).minusDays(LIST_DAYS_BACK);
        return questions.findByCommunityIdAndQuestionDayGreaterThanEqualOrderByQuestionDayAsc(community, from)
                .stream().map(DailyQuestionService::view).toList();
    }

    @Transactional
    public QuestionAdminView create(long adminId, QuestionRequest request) {
        long community = requireAdminMember(adminId);
        LocalDateTime now = now();
        LocalDate today = MomentTimes.indiaDay(now);
        LocalDate day = day(request.day());
        if (day.isBefore(today)) throw new InvalidRequestException("Choose today or a later day");
        if (day.isAfter(today.plusDays(properties.questionsAhead()))) {
            throw new InvalidRequestException("Choose a day within the next " + properties.questionsAhead() + " days");
        }
        String text = MomentPolicy.cleanLine(request.text());
        int length = MomentPolicy.length(text);
        if (length < TEXT_MIN) throw new InvalidRequestException("Write the question");
        if (length > TEXT_MAX) throw new InvalidRequestException("A question has at most 140 characters");
        if (questions.findByCommunityIdAndQuestionDay(community, day).isPresent()) throw new ConflictException(DAY_TAKEN);
        // Two admins at the same instant: the unique key refuses the second (409 from the shared handler).
        return view(questions.saveAndFlush(new DailyQuestion(community, day, text, adminId, now)));
    }

    @Transactional
    public void delete(long adminId, String publicId) {
        long community = requireAdminMember(adminId);
        DailyQuestion question = publicId == null ? null : questions.findByPublicId(publicId).orElse(null);
        if (question == null || question.getCommunityId() != community) throw new ResourceNotFoundException(NOT_FOUND);
        // Its answers stay, as ordinary moments (the database does the same: ON DELETE SET NULL).
        moments.clearQuestion(question.getId());
        questions.delete(question);
        questions.flush();
    }

    /** The admin's community; 403 unless the caller is an ADMIN (read from the database) and an active member. */
    private long requireAdminMember(long adminId) {
        boolean admin = users.findById(adminId).filter(u -> u.isEnabled() && u.getRole() == UserRole.ADMIN).isPresent();
        if (!admin) throw new ForbiddenException("Only an admin can do this");
        return access.requireMembership(adminId).communityId();
    }

    private static LocalDate day(String raw) {
        if (raw == null || raw.isBlank()) throw new InvalidRequestException("Give the day as YYYY-MM-DD");
        try {
            return LocalDate.parse(raw.trim());
        } catch (DateTimeParseException e) {
            throw new InvalidRequestException("Give the day as YYYY-MM-DD");
        }
    }

    private static QuestionAdminView view(DailyQuestion q) {
        return new QuestionAdminView(q.getPublicId(), q.getQuestionDay().toString(), q.getBody(),
                q.getCreatedAt().toInstant(ZoneOffset.UTC));
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }
}
