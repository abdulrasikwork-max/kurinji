package com.safesphere.backend.security;

import com.safesphere.backend.community.service.MembershipLookup;
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
    private final MembershipLookup memberships;

    public RoleAuthorization(UserRepository users, MembershipLookup memberships) {
        this.users = users;
        this.memberships = memberships;
    }

    public AuthorizationManager<RequestAuthorizationContext> moderator() {
        return new RoleCheck(UserRole::canModerate);
    }

    public AuthorizationManager<RequestAuthorizationContext> admin() {
        return new RoleCheck(role -> role == UserRole.ADMIN);
    }

    /**
     * Phase N3: an IRL admin, or an ACTIVE verifier of a community (a flag on their membership, read from the database
     * on every request: removing a verifier, or the end of their membership, takes effect at once).
     */
    public AuthorizationManager<RequestAuthorizationContext> verifier() {
        return new UserCheck(user -> user.getRole() == UserRole.ADMIN || memberships.isActiveVerifier(user.getId()));
    }

    private final class RoleCheck extends UserCheck {
        RoleCheck(Predicate<UserRole> allowed) {
            super(user -> allowed.test(user.getRole()));
        }
    }

    private class UserCheck implements AuthorizationManager<RequestAuthorizationContext> {
        private final Predicate<User> allowed;

        UserCheck(Predicate<User> allowed) {
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
            boolean granted = users.findById(userId).filter(User::isEnabled).filter(allowed).isPresent();
            return new AuthorizationDecision(granted);
        }
    }
}
