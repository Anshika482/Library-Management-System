package com.library.lms.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.HexFormat;
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

    /**
     * SHA-256 of the development placeholder this property used to carry.
     *
     * <p>That value sat in {@code application.properties} as the fallback for
     * {@code JWT_SECRET}, which meant the application would sign and accept
     * tokens using a key published in this repository. Removing the fallback
     * stops it being used by default; refusing it here stops it being used at
     * all, including by anyone who copies it out of the Git history into their
     * environment.</p>
     *
     * <p>Stored as a hash rather than the string itself for one practical
     * reason: a 69-character constant in a source file reads as a key, trips
     * every secret scanner, and invites someone to reuse it. A hash cannot be
     * mistaken for a usable secret and cannot be reversed into one, while still
     * recognising the value if it turns up. The preimage is public in this
     * repository's history and is exercised by
     * {@code JwtServiceSecretValidationTest}.</p>
     */
    private static final String RETIRED_PLACEHOLDER_SHA256 =
            "161807e398c0585c9d76c4d01bc2d4121851c7c537750ab94ae41f426cefdbaf";

    private final SecretKey signingKey;

    /**
     * Builds the signing key once, at startup.
     *
     * <p>Doing this in a constructor is the point. A secret that is unusable
     * would otherwise be discovered on the first login attempt, in production,
     * as a failed request; here it stops the application from starting at all
     * and says exactly what is wrong. Every message below describes the secret
     * without quoting it, so a startup failure never prints the value it is
     * complaining about.</p>
     *
     * <p>Three ways a secret is refused, in the order they are checked:</p>
     * <ul>
     *   <li><b>Blank</b> - {@code JWT_SECRET} set to an empty or whitespace
     *       value. Spring's {@code ${JWT_SECRET}} placeholder catches the
     *       variable being <i>absent</i>, but an empty variable resolves
     *       perfectly well to an empty string, so it has to be caught here.
     *       Checked first because the length check below would otherwise
     *       report it as merely "too short", which sends the reader looking
     *       for the wrong problem.</li>
     *   <li><b>The retired placeholder</b> - see
     *       {@link #RETIRED_PLACEHOLDER_SHA256}. It is long enough to pass the
     *       length check, so nothing else here would stop it.</li>
     *   <li><b>Too short</b> - the original check, unchanged. HS256 needs 256
     *       bits and refuses a shorter key rather than signing weakly.</li>
     * </ul>
     *
     * @param secret the configured signing secret, from {@code jwt.secret}
     * @throws IllegalStateException if the secret is blank, is the retired
     *                               development placeholder, or is too short
     *                               to sign safely
     */
    public JwtService(@Value("${jwt.secret}") String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "jwt.secret resolved to a blank value. Set the JWT_SECRET environment"
                            + " variable to a private secret of at least "
                            + MINIMUM_SECRET_LENGTH_BYTES + " bytes.");
        }

        if (RETIRED_PLACEHOLDER_SHA256.equalsIgnoreCase(sha256Hex(secret))) {
            throw new IllegalStateException(
                    "jwt.secret is the development placeholder that used to be committed to"
                            + " application.properties. That value is public in this repository's"
                            + " history, so anyone holding it can mint a token for any account."
                            + " Set the JWT_SECRET environment variable to a private value.");
        }

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
     * Hex-encoded SHA-256 of a candidate secret, used only for the denylist
     * comparison above.
     *
     * <p>This is not password hashing and is not trying to be: there is no salt
     * and no work factor, because the question is "is this exactly the one
     * known-bad value" rather than "does this match a stored credential". A
     * fast digest is the right tool for an equality test against a published
     * string.</p>
     *
     * <p>SHA-256 is required of every Java platform, so the checked exception
     * cannot actually occur; it is rethrown rather than swallowed because a JVM
     * without it is broken in a way that must not be papered over. The message
     * carries no part of the input.</p>
     */
    private static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));

            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable on this JVM", exception);
        }
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
