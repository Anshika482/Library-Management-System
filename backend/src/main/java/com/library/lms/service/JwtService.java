package com.library.lms.service;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;

import javax.crypto.SecretKey;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

/**
 * Mints signed JSON Web Tokens.
 *
 * <p>A JWT is not encrypted. Anyone holding one can read the username and roles
 * inside it; what they cannot do is change them, because the signature would
 * stop matching. That is the whole value of the thing: the server can trust a
 * token it issued earlier without keeping any session state to check it
 * against.</p>
 *
 * <p>This class only creates tokens. Reading one back, deciding whether it is
 * still valid and letting it authenticate a request are separate concerns and
 * are not implemented here; nothing calls this service yet.</p>
 */
@Service
public class JwtService {

    /**
     * How long an issued token stays valid.
     *
     * <p>Short on purpose. A token cannot be withdrawn once handed out - there
     * is no list of revoked tokens to consult - so the only real limit on a
     * stolen one is how quickly it expires. An hour is a temporary figure for
     * development, not a considered policy.</p>
     */
    private static final Duration TOKEN_VALIDITY = Duration.ofHours(1);

    /** HS256 requires a key of at least 256 bits, which is 32 bytes. */
    private static final int MINIMUM_SECRET_LENGTH_BYTES = 32;

    private final SecretKey signingKey;

    /**
     * Builds the signing key once, at startup.
     *
     * <p>The length check is the point of doing this in a constructor. A secret
     * shorter than HS256 requires would otherwise be discovered on the first
     * login attempt, in production, as a failed request; here it stops the
     * application from starting at all and says exactly what is wrong. The
     * message deliberately describes the secret without quoting it.</p>
     *
     * @param secret the configured signing secret, from {@code jwt.secret}
     * @throws IllegalStateException if the secret is too short to sign safely
     */
    public JwtService(@Value("${jwt.secret}") String secret) {
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);

        if (keyBytes.length < MINIMUM_SECRET_LENGTH_BYTES) {
            throw new IllegalStateException(
                    "jwt.secret is too short to sign with HS256: it must be at least "
                            + MINIMUM_SECRET_LENGTH_BYTES
                            + " bytes. Set the JWT_SECRET environment variable to a longer value.");
        }

        this.signingKey = Keys.hmacShaKeyFor(keyBytes);
    }

    /**
     * Issues a token for an account that has already been authenticated.
     *
     * <p>The parameter type matters. Taking {@link UserDetails} means this
     * method cannot be called with a username someone merely typed - it needs
     * the object Spring Security produces after checking a password, so a
     * token can only be minted for an identity that was actually proved.</p>
     *
     * <p>What goes in, and why only this:</p>
     * <ul>
     *   <li><b>subject</b> - who the token is about. The username, because that
     *       is what identifies an account everywhere else in this system.</li>
     *   <li><b>roles</b> - the authorities, so a later request can be
     *       authorized without another database lookup.</li>
     *   <li><b>issued at</b> and <b>expiration</b> - when it was minted and
     *       when it stops counting.</li>
     * </ul>
     *
     * <p>The password hash is not among them, and neither is anything else from
     * the user row. A JWT payload is Base64, not ciphertext: every claim here
     * is readable by whoever holds the token.</p>
     *
     * @param userDetails the authenticated account
     * @return a signed, compact JWT
     */
    public String generateToken(UserDetails userDetails) {
        List<String> roles = userDetails.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .toList();

        Instant issuedAt = Instant.now();
        Instant expiresAt = issuedAt.plus(TOKEN_VALIDITY);

        return Jwts.builder()
                .subject(userDetails.getUsername())
                .claim("roles", roles)
                .issuedAt(Date.from(issuedAt))
                .expiration(Date.from(expiresAt))
                .signWith(signingKey, Jwts.SIG.HS256)
                .compact();
    }

    /**
     * Validates a token and returns the username it was issued for.
     *
     * <p>Validation and extraction are one method because they cannot safely be
     * two. Reading a claim out of an unverified token is the classic JWT
     * mistake: the payload is only Base64, so anyone can write any subject they
     * like into one. Parsing here goes through {@code verifyWith}, so the
     * signature is checked against the same key used to sign, and a subject is
     * only ever returned from a token this server actually issued.</p>
     *
     * <p>Three things are checked, all by the parser rather than by hand:</p>
     * <ul>
     *   <li><b>Structure</b> - anything that is not a well formed JWS is
     *       rejected, including a token that is merely truncated.</li>
     *   <li><b>Signature</b> - recomputed with {@link #signingKey}. A single
     *       altered character in the payload makes it fail.</li>
     *   <li><b>Expiration</b> - the parser compares the {@code exp} claim
     *       against the clock and refuses a token that has run out.</li>
     * </ul>
     *
     * <p>Failure is an exception, not a null or a false. A method that returned
     * a username and separately reported validity would let a caller use the
     * first and forget the second; this one gives the caller nothing to misuse.
     * The exception carries no token material into the caller's hands.</p>
     *
     * @param token the compact JWS from the Authorization header
     * @return the subject of a token that passed every check
     * @throws io.jsonwebtoken.JwtException if the token is malformed, expired,
     *                                      unsupported or wrongly signed
     * @throws IllegalArgumentException     if the token is null or blank
     */
    public String extractUsername(String token) {
        return Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload()
                .getSubject();
    }
}
