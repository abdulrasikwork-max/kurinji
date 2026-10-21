package com.safesphere.backend.security;

import com.safesphere.backend.user.entity.User;
import com.safesphere.backend.user.entity.UserRole;
import com.safesphere.backend.user.repository.UserRepository;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.stereotype.Component;

/**
 * Phase 7 role checks for SecurityConfig. The role is read from the DATABASE on every request (users.role), never
 * from a token claim or anything the client sends: removing a moderator takes effect on their very next request,
 * and a stolen or old token cannot carry a role. A disabled account is never authorised.
 */
@Component
public class RoleAuthorization {

    private final UserRepository users;

    public RoleAuthorization(UserRepository users) {
        this.users = users;
    }

    public AuthorizationManager<RequestAuthorizationContext> moderator() {
        return new RoleCheck(UserRole::canModerate);
    }

    public AuthorizationManager<RequestAuthorizationContext> admin() {
        return new RoleCheck(role -> role == UserRole.ADMIN);
    }

    private final class RoleCheck implements AuthorizationManager<RequestAuthorizationContext> {
        private final Predicate<UserRole> allowed;

        RoleCheck(Predicate<UserRole> allowed) {
            this.allowed = allowed;
        }

        @Override
        @SuppressWarnings("deprecation") // check() is still the method Spring Security 6.x calls (via authorize())
        public AuthorizationDecision check(Supplier<Authentication> authentication, RequestAuthorizationContext context) {
            Authentication current = authentication.get();
            if (!(current instanceof JwtAuthenticationToken token) || !current.isAuthenticated()) {
                return new AuthorizationDecision(false);
            }
            long userId;
            try {
                userId = Long.parseLong(token.getToken().getSubject());
            } catch (NumberFormatException e) {
                return new AuthorizationDecision(false);
            }
            boolean granted = users.findById(userId).filter(User::isEnabled).map(User::getRole).filter(allowed).isPresent();
            return new AuthorizationDecision(granted);
        }
    }
}
