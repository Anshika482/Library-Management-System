package com.library.lms.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;

import javax.crypto.SecretKey;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.SignatureException;

/**
 * Verifies {@link JwtService} against the tokens it actually produces.
 *
 * <p>No Spring context is started. The service takes its secret through a
 * constructor parameter, so it can be built directly, and doing so keeps this
 * test away from the datasource entirely: nothing here can reach MySQL, load a
 * user or touch a row even by accident.</p>
 *
 * <p>The secret below is a value invented for this file. Using the configured
 * one would prove nothing extra - the behaviour under test is the same for any
 * key of sufficient length - and would drag a real signing secret into the test
 * sources. The {@link UserDetails} is likewise built in memory and never
 * saved.</p>
 */
class JwtServiceTest {

    private static final String TEST_SECRET =
            "test-only-secret-for-unit-tests-not-used-by-any-environment";

    private static final String TEST_ISSUER = "jwt-service-test-issuer";

    private static final String TEST_AUDIENCE = "jwt-service-test-audience";

    /** An ordinary lifetime, so these tests exercise everything but the validity check. */
    private static final Duration TEST_VALIDITY = Duration.ofHours(1);

    private static final SecretKey TEST_KEY =
            Keys.hmacShaKeyFor(TEST_SECRET.getBytes(StandardCharsets.UTF_8));

    private static final String TEST_USERNAME = "test-user";

    private static final String TEST_AUTHORITY = "ROLE_MEMBER";

    private final JwtService jwtService = new JwtService(TEST_SECRET, TEST_ISSUER, TEST_AUDIENCE, TEST_VALIDITY);

    private static UserDetails testUser() {
        return User.withUsername(TEST_USERNAME)
                .password("placeholder-never-persisted")
                .authorities(TEST_AUTHORITY)
                .build();
    }

    /** Reads the claims back through a verifying parse, never a blind decode. */
    private static Claims claimsOf(String token) {
        return Jwts.parser().verifyWith(TEST_KEY).build().parseSignedClaims(token).getPayload();
    }

    @Test
    void generatesATokenOfThreeSegments() {
        String token = jwtService.generateToken(testUser());

        assertThat(token).isNotBlank();
        assertThat(token.split("[.]")).as("header, payload and signature").hasSize(3);
    }

    @Test
    void tokenCarriesSubjectIssuerAudienceAndLifetimeButNoRole() {
        Claims claims = claimsOf(jwtService.generateToken(testUser()));

        assertThat(claims.getSubject()).isEqualTo(TEST_USERNAME);
        // No roles claim. Authorization reloads the caller's authorities from
        // the database on every request and never reads one, so the token
        // carries only who it is about, who issued it, who it is for, and how
        // long it lasts.
        assertThat(claims).doesNotContainKey("roles");
        assertThat(claims.keySet())
                .as("the complete claim set - nothing else rides along")
                .containsExactlyInAnyOrder("sub", "iat", "exp", "iss", "aud");
        assertThat(claims.getIssuer()).isEqualTo(TEST_ISSUER);
        assertThat(claims.getAudience()).containsExactly(TEST_AUDIENCE);
        assertThat(claims.getIssuedAt()).isNotNull();
        assertThat(claims.getExpiration()).isNotNull();
        assertThat(claims.getExpiration()).isAfter(claims.getIssuedAt());

        long seconds = (claims.getExpiration().getTime() - claims.getIssuedAt().getTime()) / 1000L;
        assertThat(seconds).as("configured validity is one hour").isEqualTo(3600L);
    }

    @Test
    void extractUsernameReturnsTheSubjectOfAValidToken() {
        String token = jwtService.generateToken(testUser());

        assertThat(jwtService.extractUsername(token)).isEqualTo(TEST_USERNAME);
    }

    @Test
    void rejectsATokenWhoseSignatureWasTampered() {
        String[] parts = jwtService.generateToken(testUser()).split("[.]");
        char first = parts[2].charAt(0);
        String tampered = (first == 'A' ? 'B' : 'A') + parts[2].substring(1);
        String forged = parts[0] + "." + parts[1] + "." + tampered;

        assertThatThrownBy(() -> jwtService.extractUsername(forged))
                .isInstanceOf(SignatureException.class);
    }

    @Test
    void rejectsATokenSignedWithADifferentKey() {
        SecretKey attackerKey = Keys.hmacShaKeyFor(
                "an-entirely-different-key-the-server-never-issued".getBytes(StandardCharsets.UTF_8));
        String selfMinted = Jwts.builder()
                .subject("admin")
                .issuer(TEST_ISSUER)
                .audience().add(TEST_AUDIENCE).and()
                .claim("roles", List.of("ROLE_ADMIN"))
                .issuedAt(Date.from(Instant.now()))
                .expiration(Date.from(Instant.now().plus(1, ChronoUnit.HOURS)))
                .signWith(attackerKey, Jwts.SIG.HS256)
                .compact();

        assertThatThrownBy(() -> jwtService.extractUsername(selfMinted))
                .isInstanceOf(SignatureException.class);
    }

    @Test
    void rejectsAnExpiredToken() {
        Instant issued = Instant.now().minus(2, ChronoUnit.HOURS);
        String expired = Jwts.builder()
                .subject(TEST_USERNAME)
                .issuer(TEST_ISSUER)
                .audience().add(TEST_AUDIENCE).and()
                .claim("roles", List.of(TEST_AUTHORITY))
                .issuedAt(Date.from(issued))
                .expiration(Date.from(issued.plus(1, ChronoUnit.HOURS)))
                .signWith(TEST_KEY, Jwts.SIG.HS256)
                .compact();

        assertThatThrownBy(() -> jwtService.extractUsername(expired))
                .isInstanceOf(ExpiredJwtException.class);
    }
}
