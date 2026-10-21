package com.safesphere.backend.user.service;

import com.safesphere.backend.common.exception.UnauthorizedException;
import com.safesphere.backend.user.dto.UserResponse;
import com.safesphere.backend.user.entity.User;
import com.safesphere.backend.user.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService {

    private final UserRepository userRepository;

    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
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
