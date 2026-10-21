package com.safesphere.backend.security;

import com.safesphere.backend.config.JwtProperties;
import com.safesphere.backend.user.entity.User;
import java.time.Clock;
import java.time.Instant;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

/**
 * Creates login tokens. This is the ONLY place a JWT is built, so token rules live in one spot.
 *
 * A token contains: iss (who issued it), sub (the user id), iat (issued at) and exp (expires at).
 * It does not contain the password or the password hash. A JWT is signed, not encrypted: anyone
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

    public IssuedToken issueToken(User user) {
        Instant now = Instant.now(clock);
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.issuer())
                .issuedAt(now)
                .expiresAt(now.plus(properties.expiration()))
                .subject(String.valueOf(user.getId()))
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();

        String token = jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new IssuedToken(token, properties.expiration().toSeconds());
    }
}
