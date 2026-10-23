package com.safesphere.backend.social.service;

import com.safesphere.backend.common.exception.ConflictException;
import com.safesphere.backend.common.exception.UnauthorizedException;
import com.safesphere.backend.user.entity.User;
import com.safesphere.backend.user.repository.UserRepository;
import org.springframework.stereotype.Component;

/**
 * Phase 13A: who may use the social features at all. The caller's account must be active (ActiveAccountCheck already
 * refuses tokens of disabled or erased accounts; checked again here for clarity) and must have declared an adult date
 * of birth (D6). Safety features never ask this: an SOS works for every signed-in account.
 */
@Component
public class SocialAccess {

    public static final String CONFIRM_AGE_MESSAGE = "Confirm your age to use this";

    private final UserRepository users;

    public SocialAccess(UserRepository users) {
        this.users = users;
    }

    public User requireMember(long userId) {
        User user = users.findById(userId)
                .filter(u -> u.isEnabled() && !u.isErased())
                .orElseThrow(() -> new UnauthorizedException("Account is not available"));
        if (!user.isAgeConfirmed()) throw new ConflictException(CONFIRM_AGE_MESSAGE);
        return user;
    }

    /** Active = can be seen by others at all (not disabled, not erased). */
    public boolean isActive(long userId) {
        return users.findById(userId).map(u -> u.isEnabled() && !u.isErased()).orElse(false);
    }
}
