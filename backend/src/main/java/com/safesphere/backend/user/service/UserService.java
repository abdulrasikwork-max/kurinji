package com.safesphere.backend.user.service;

import com.safesphere.backend.common.exception.UnauthorizedException;
import com.safesphere.backend.user.dto.UserResponse;
import com.safesphere.backend.user.entity.User;
import com.safesphere.backend.user.repository.UserRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService {

    private final UserRepository userRepository;
    private final AgeGate ageGate;
    private final Clock clock;

    public UserService(UserRepository userRepository, AgeGate ageGate, Clock clock) {
        this.userRepository = userRepository;
        this.ageGate = ageGate;
        this.clock = clock;
    }

    /**
     * Phase 13A: an account created before V20 declares an adult date of birth once (the social features need it; the
     * safety features never do). Idempotent: an account that already confirmed stays confirmed with its first time.
     */
    @Transactional
    public void confirmAge(long userId, LocalDate dateOfBirth) {
        ageGate.requireAdult(dateOfBirth);
        User user = userRepository.findById(userId)
                .filter(User::isEnabled)
                .orElseThrow(() -> new UnauthorizedException("Account is not available"));
        user.confirmAge(LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
    }

    /**
     * Loads the profile of the user a valid token belongs to. If that account was deleted or
     * disabled after the token was issued, the token is no longer good enough.
     */
    @Transactional(readOnly = true)
    public UserResponse getProfile(long userId) {
        User user = userRepository.findById(userId)
                .filter(User::isEnabled)
                .orElseThrow(() -> new UnauthorizedException("Account is not available"));
        return UserResponse.from(user);
    }
}
