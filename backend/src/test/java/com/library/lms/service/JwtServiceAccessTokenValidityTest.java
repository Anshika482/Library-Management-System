package com.library.lms.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Parameter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Properties;

import javax.crypto.SecretKey;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

/**
 * The access token's lifetime: configured, not compiled in.
 *
 * <p>An access token cannot be withdrawn once issued, so its lifetime is the
 * window in which a stolen or logged-out one still works. It was an hour,
 * hard-coded; it is now {@code security.access-token.validity}, an hour by
 * default, and what this class checks is that the configured value is the one
 * tokens actually carry.</p>
 *
 * <p>No Spring context is started: the service takes the duration through its
 * constructor. That the constructor is wired to the property is checked by
 * reading the annotation, and that the property ships with the intended default
 * by reading the properties file - between them they cover the wiring a context
 * would exercise, in milliseconds rather than seconds.</p>
 *
 * <p>The secret below is invented for this file, and no assertion prints a
 * token or a secret.</p>
 */
class JwtServiceAccessTokenValidityTest {

    private static final String TEST_SECRET =
            "test-only-secret-for-validity-tests-not-used-by-any-environment";

    private static final String TEST_ISSUER = "jwt-validity-test-issuer";

    private static final String TEST_AUDIENCE = "jwt-validity-test-audience";

    private static final String TEST_USERNAME = "validity-test-user";

    private static final SecretKey TEST_KEY =
            Keys.hmacShaKeyFor(TEST_SECRET.getBytes(StandardCharsets.UTF_8));

    /** The default the property carries, stated once so every expectation below reads from it. */
    private static final Duration DEFAULT_VALIDITY = Duration.ofHours(1);

    private static UserDetails testUser() {
        return User.withUsername(TEST_USERNAME)
                .password("placeholder-never-persisted")
                .authorities("ROLE_MEMBER")
                .build();
    }

    private static JwtService serviceWith(Duration validity) {
        return new JwtService(TEST_SECRET, TEST_ISSUER, TEST_AUDIENCE, validity);
    }

    /** Reads the claims back through a verifying parse, never a blind decode. */
    private static Claims claimsOf(String token) {
        return Jwts.parser().verifyWith(TEST_KEY).build().parseSignedClaims(token).getPayload();
    }

    /** How long the token lasts, as it is actually stamped: exp minus iat. */
    private static Duration lifetimeOf(String token) {
        Claims claims = claimsOf(token);

        return Duration.ofMillis(claims.getExpiration().getTime() - claims.getIssuedAt().getTime());
    }

    // ---------- the default ----------

    @Test
    void theShippedConfigurationDefaultsToOneHour() throws Exception {
        Properties configuration = new Properties();
        try (InputStream file = getClass().getResourceAsStream("/application.properties")) {
            assertThat(file).isNotNull();
            configuration.load(file);
        }

        assertThat(configuration.getProperty(JwtService.VALIDITY_PROPERTY))
                .as("configurable, and an hour unless the environment says otherwise")
                .isEqualTo("${JWT_ACCESS_TOKEN_VALIDITY:PT1H}");
    }

    @Test
    void theConstructorTakesTheLifetimeFromThatProperty() throws Exception {
        Constructor<JwtService> constructor = JwtService.class.getConstructor(
                String.class, String.class, String.class, Duration.class);
        Parameter validity = constructor.getParameters()[3];

        assertThat(validity.getAnnotation(Value.class)).isNotNull();
        assertThat(validity.getAnnotation(Value.class).value())
                .isEqualTo("${" + JwtService.VALIDITY_PROPERTY + "}");
    }

    @Test
    void theDefaultLifetimeIsStampedOnTheToken() {
        String token = serviceWith(DEFAULT_VALIDITY).generateToken(testUser());

        assertThat(lifetimeOf(token)).isEqualTo(DEFAULT_VALIDITY);
        assertThat(lifetimeOf(token)).as("one hour, in seconds").isEqualTo(Duration.ofSeconds(3600));
    }

    // ---------- a configured lifetime ----------

    @ParameterizedTest
    @ValueSource(strings = {"PT5M", "PT15M", "PT30M", "PT2H", "P1D"})
    void aConfiguredLifetimeIsTheOneTokensCarry(String configured) {
        Duration validity = Duration.parse(configured);

        String token = serviceWith(validity).generateToken(testUser());

        assertThat(lifetimeOf(token)).isEqualTo(validity);
    }

    @Test
    void onlyTheLifetimeChanges() {
        Claims claims = claimsOf(serviceWith(Duration.ofMinutes(5)).generateToken(testUser()));

        assertThat(claims.keySet())
                .as("the same claim set as before, whatever the lifetime")
                .containsExactlyInAnyOrder("sub", "iat", "exp", "iss", "aud");
        assertThat(claims.getSubject()).isEqualTo(TEST_USERNAME);
        assertThat(claims.getIssuer()).isEqualTo(TEST_ISSUER);
        assertThat(claims.getAudience()).containsExactly(TEST_AUDIENCE);
    }

    // ---------- refusals ----------

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"PT0S", "PT-1S", "PT-1H", "-P1D"})
    void aMissingZeroOrNegativeLifetimeStopsStartup(String configured) {
        Duration validity = configured == null ? null : Duration.parse(configured);

        assertThatThrownBy(() -> serviceWith(validity))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(JwtService.VALIDITY_PROPERTY)
                .hasMessageContaining("JWT_ACCESS_TOKEN_VALIDITY")
                .hasMessageNotContaining(TEST_SECRET);
    }

    @Test
    void theSmallestPositiveLifetimeIsAccepted() {
        assertThatCode(() -> serviceWith(Duration.ofSeconds(1))).doesNotThrowAnyException();
    }

    // ---------- expiry ----------

    @Test
    void aTokenIsRefusedOnceItsConfiguredLifetimeHasPassed() throws Exception {
        JwtService shortLived = serviceWith(Duration.ofSeconds(1));
        String token = shortLived.generateToken(testUser());

        assertThat(shortLived.extractUsername(token)).as("valid while it lasts").isEqualTo(TEST_USERNAME);

        // exp is stamped in whole seconds, so a one-second token has run out by
        // the time 1.5 of them have passed - without depending on how long the
        // assertion above took.
        Thread.sleep(1_500);

        assertThatThrownBy(() -> shortLived.extractUsername(token)).isInstanceOf(ExpiredJwtException.class);
    }

    @Test
    void aLongerLifetimeIsStillValidAtThatSameMoment() throws Exception {
        JwtService longLived = serviceWith(DEFAULT_VALIDITY);
        String token = longLived.generateToken(testUser());

        Thread.sleep(1_500);

        assertThat(longLived.extractUsername(token))
                .as("the refusal above is the lifetime running out, not the passage of time")
                .isEqualTo(TEST_USERNAME);
    }
}
