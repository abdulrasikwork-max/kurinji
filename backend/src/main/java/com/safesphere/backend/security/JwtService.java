package com.safesphere.backend.security;

import com.safesphere.backend.config.JwtProperties;
import com.safesphere.backend.user.entity.User;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

/**
 * Creates login tokens. This is the ONLY place a JWT is built, so token rules live in one spot.
 *
 * A token contains: iss (who issued it), sub (the user id), iat (issued at) and exp (expires at), plus, once the
 * password was changed, cca (the credentials stamp, Phase 10A). Since Phase 10A.4 also sid (a random id of this
 * sign-in, kept by renewals) and auth_time (when the password was last typed for it). It does not contain the password
 * or the password hash. A JWT is signed, not encrypted: anyone
 * holding it can read these claims, which is why nothing sensitive goes in.
 */
@Service
public class JwtService {

    /** The token string and how many seconds it stays valid. */
    public record IssuedToken(String value, long expiresInSeconds) {
    }

    private final JwtEncoder jwtEncoder;
    private final JwtProperties properties;
    private final Clock clock;

    public JwtService(JwtEncoder jwtEncoder, JwtProperties properties) {
        this.jwtEncoder = jwtEncoder;
        this.properties = properties;
        this.clock = Clock.systemUTC();
    }

    /** A NEW sign-in (register, login): a new session id, signed in now. */
    public IssuedToken issueToken(User user) {
        Instant now = Instant.now(clock);
        return issueToken(user, UUID.randomUUID().toString(), now);
    }

    /**
     * Phase 10A.4: a token for an existing session (renewal, password change) keeps its [sessionId] ("sid": which
     * phone's sign-in this is) and [authTime] ("auth_time": when the person last typed the password there).
     */
    public IssuedToken issueToken(User user, String sessionId, Instant authTime) {
        Instant now = Instant.now(clock);
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .issuer(properties.issuer())
                .issuedAt(now)
                .expiresAt(now.plus(properties.expiration()))
                .subject(String.valueOf(user.getId()))
                .claim(CurrentUser.SESSION_CLAIM, sessionId)
                .claim(CurrentUser.AUTH_TIME_CLAIM, authTime.getEpochSecond());
        // Phase 10A: the credentials stamp at issue time; ActiveAccountCheck refuses the token once the stamp changes.
        Long stamp = user.credentialsStamp();
        if (stamp != null) claims.claim(ActiveAccountCheck.STAMP_CLAIM, stamp);
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();

        String token = jwtEncoder.encode(JwtEncoderParameters.from(header, claims.build())).getTokenValue();
        return new IssuedToken(token, properties.expiration().toSeconds());
    }
}
