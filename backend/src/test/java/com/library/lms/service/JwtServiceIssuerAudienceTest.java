package com.library.lms.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;

import javax.crypto.SecretKey;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.IncorrectClaimException;
import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.MissingClaimException;
import io.jsonwebtoken.security.Keys;

/**
 * Pins the issuer and audience: stamped on every token {@link JwtService}
 * issues, and demanded of every token it accepts.
 *
 * <p>Neither claim is secret, so neither makes a token trustworthy - the
 * signature does. What they add is separation. Every token below that is
 * refused is <b>genuinely signed with the right key</b>; the only thing wrong
 * with it is who issued it or who it is for. That is exactly the case the
 * signature cannot catch: two deployments that share a key by mistake would
 * otherwise accept each other's tokens.</p>
 *
 * <p>Wrong and missing are asserted as different exception types -
 * {@link IncorrectClaimException} and {@link MissingClaimException} - so each
 * test proves the reason for the refusal rather than merely that one happened.
 * Both are {@code JwtException}s, which is what {@code JwtAuthenticationFilter}
 * catches, so at the HTTP boundary every one of them is the existing 401.</p>
 *
 * <p>No Spring context. The service is built directly, which is also how the
 * blank-configuration cases are reached: in a running application the same
 * refusal stops the context from starting.</p>
 */
class JwtServiceIssuerAudienceTest {

    private static final String SECRET = "issuer-audience-test-secret-comfortably-over-32-bytes";

    private static final SecretKey KEY = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));

    private static final String ISSUER = "issuer-audience-test-issuer";

    private static final String AUDIENCE = "issuer-audience-test-audience";

    /** An ordinary lifetime, so these tests exercise everything but the validity check. */
    private static final Duration TEST_VALIDITY = Duration.ofHours(1);

    private static final String USERNAME = "issuer-audience-user";

    private final JwtService jwtService = new JwtService(SECRET, ISSUER, AUDIENCE, TEST_VALIDITY);

    private static UserDetails user() {
        return User.withUsername(USERNAME)
                .password("placeholder-never-persisted")
                .authorities("ROLE_MEMBER")
                .build();
    }

    /** Reads claims back through a verifying parse that checks the signature only. */
    private static Claims claimsOf(String token) {
        return Jwts.parser().verifyWith(KEY).build().parseSignedClaims(token).getPayload();
    }

    /**
     * Mints an unexpired token with the right key; only the issuer and audience
     * vary. {@code null} leaves that claim out entirely.
     */
    private static String mint(String issuer, String audience) {
        JwtBuilder builder = Jwts.builder()
                .subject(USERNAME)
                .issuedAt(Date.from(Instant.now()))
                .expiration(Date.from(Instant.now().plus(1, ChronoUnit.HOURS)));
        if (issuer != null) {
            builder.issuer(issuer);
        }
        if (audience != null) {
            builder.audience().add(audience).and();
        }
        return builder.signWith(KEY, Jwts.SIG.HS256).compact();
    }

    // ---------- what a new token carries ----------

    @Test
    void aNewTokenCarriesTheConfiguredIssuer() {
        assertThat(claimsOf(jwtService.generateToken(user())).getIssuer()).isEqualTo(ISSUER);
    }

    @Test
    void aNewTokenCarriesTheConfiguredAudience() {
        assertThat(claimsOf(jwtService.generateToken(user())).getAudience()).containsExactly(AUDIENCE);
    }

    @Test
    void aNewTokenStillYieldsItsSubject() {
        assertThat(jwtService.extractUsername(jwtService.generateToken(user()))).isEqualTo(USERNAME);
    }

    // ---------- what an accepted token must carry ----------

    @Test
    void aTokenWithTheRightIssuerAndAudienceIsAccepted() {
        // The control for every refusal below: same helper, same key, only
        // right. Without it a refusal could mean the helper was broken.
        assertThat(jwtService.extractUsername(mint(ISSUER, AUDIENCE))).isEqualTo(USERNAME);
    }

    @Test
    void aWrongIssuerIsRejected() {
        assertThatThrownBy(() -> jwtService.extractUsername(mint("some-other-issuer", AUDIENCE)))
                .isInstanceOf(IncorrectClaimException.class);
    }

    @Test
    void aWrongAudienceIsRejected() {
        assertThatThrownBy(() -> jwtService.extractUsername(mint(ISSUER, "some-other-api")))
                .isInstanceOf(IncorrectClaimException.class);
    }

    @Test
    void aMissingIssuerIsRejected() {
        assertThatThrownBy(() -> jwtService.extractUsername(mint(null, AUDIENCE)))
                .isInstanceOf(MissingClaimException.class);
    }

    @Test
    void aMissingAudienceIsRejected() {
        assertThatThrownBy(() -> jwtService.extractUsername(mint(ISSUER, null)))
                .isInstanceOf(MissingClaimException.class);
    }

    @Test
    void aTokenIssuedBeforeIssuerAndAudienceExistedIsRejected() {
        // The exact shape issued until this change - subject and lifetime,
        // genuinely signed - is deliberately no longer valid.
        assertThatThrownBy(() -> jwtService.extractUsername(mint(null, null)))
                .isInstanceOf(MissingClaimException.class);
    }

    @Test
    void aTokenFromAnotherDeploymentSharingTheKeyIsRejected() {
        // The scenario the two claims exist for. Same key, so the signature
        // verifies; different names, so the token is still refused.
        JwtService otherDeployment = new JwtService(SECRET, "another-deployment", "another-api", TEST_VALIDITY);
        String theirs = otherDeployment.generateToken(user());

        assertThat(otherDeployment.extractUsername(theirs))
                .as("valid where it was issued")
                .isEqualTo(USERNAME);
        assertThatThrownBy(() -> jwtService.extractUsername(theirs))
                .as("refused here")
                .isInstanceOf(IncorrectClaimException.class);
    }

    // ---------- configuration fails closed ----------

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   "})
    void aBlankIssuerStopsTheServiceFromBeingBuilt(String issuer) {
        assertThatThrownBy(() -> new JwtService(SECRET, issuer, AUDIENCE, TEST_VALIDITY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_ISSUER");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   "})
    void aBlankAudienceStopsTheServiceFromBeingBuilt(String audience) {
        assertThatThrownBy(() -> new JwtService(SECRET, ISSUER, audience, TEST_VALIDITY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_AUDIENCE");
    }
}
