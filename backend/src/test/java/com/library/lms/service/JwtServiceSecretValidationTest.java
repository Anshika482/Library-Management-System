package com.library.lms.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Proves that a signing secret the application must not use stops it from
 * starting.
 *
 * <p>{@link JwtService} takes its secret through the constructor, and that
 * constructor is invoked while the Spring context is being built. So every
 * refusal asserted here is, in a running application, a startup failure - the
 * context never completes, no port is opened and no request is ever served.
 * Constructing the service directly tests exactly that behaviour without paying
 * for a context, which is why this class starts no Spring anything and runs in
 * milliseconds.</p>
 *
 * <p>The companion {@code JwtServiceTest} covers what a <i>valid</i> secret
 * produces - signing, parsing, expiry, tampering. This class covers only the
 * refusals, which arrived with the removal of the committed
 * {@code JWT_SECRET} fallback.</p>
 */
class JwtServiceSecretValidationTest {

    /**
     * A secret that is long enough and is not the retired one.
     *
     * <p>Invented for this file. It is never used to sign anything that leaves
     * the test JVM, and no environment uses it.</p>
     */
    private static final String ACCEPTABLE_SECRET =
            "step133-valid-test-secret-comfortably-over-thirty-two-bytes";

    /** A valid issuer and audience, so every refusal below is about the secret alone. */
    private static final String ISSUER = "secret-validation-test-issuer";

    private static final String AUDIENCE = "secret-validation-test-audience";

    /**
     * The development placeholder that {@code application.properties} used to
     * carry as the fallback for {@code JWT_SECRET}.
     *
     * <p>It is written out in full here on purpose, and it is safe to: the
     * value has been public in this repository's history since the property was
     * first added, and it is now refused outright, so it authenticates nothing
     * anywhere. Holding the literal is what lets this test prove the refusal
     * end to end rather than merely asserting that some hash constant is
     * well-formed - if someone edits or drops
     * {@code RETIRED_PLACEHOLDER_SHA256}, this fails.</p>
     */
    private static final String RETIRED_PLACEHOLDER =
            "dev-only-placeholder-not-a-real-secret-replace-via-JWT_SECRET-env-var";

    // ---------- the retired placeholder ----------

    @Test
    void theRetiredDevelopmentPlaceholderIsRefused() {
        assertThatThrownBy(() -> new JwtService(RETIRED_PLACEHOLDER, ISSUER, AUDIENCE))
                .as("the signing key published in this repository must not start the application")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("placeholder");
    }

    @Test
    void theRetiredPlaceholderIsLongEnoughToPassEveryOtherCheck() {
        // Without this, the test above would still pass if the placeholder
        // happened to be rejected merely for being short - and the denylist
        // could be deleted without anything noticing.
        assertThat(RETIRED_PLACEHOLDER.length())
                .as("so the only thing that can be refusing it is the denylist")
                .isGreaterThanOrEqualTo(32);
    }

    // ---------- blank ----------

    @Test
    void anEmptySecretIsRefused() {
        // JWT_SECRET= in the environment. The ${JWT_SECRET} placeholder
        // resolves happily to an empty string, so only this check stops it.
        assertThatThrownBy(() -> new JwtService("", ISSUER, AUDIENCE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("blank");
    }

    @Test
    void aWhitespaceOnlySecretIsRefused() {
        assertThatThrownBy(() -> new JwtService("    ", ISSUER, AUDIENCE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("blank");
    }

    @Test
    void aNullSecretIsRefused() {
        assertThatThrownBy(() -> new JwtService(null, ISSUER, AUDIENCE))
                .as("a clear startup message, not a NullPointerException")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("blank");
    }

    // ---------- length, unchanged ----------

    @Test
    void aSecretShorterThanHs256RequiresIsStillRefused() {
        assertThatThrownBy(() -> new JwtService("far-too-short", ISSUER, AUDIENCE))
                .as("the original length check must survive the new ones")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("too short");
    }

    @Test
    void aSecretOfExactlyThirtyTwoBytesIsAccepted() {
        // The boundary, from the other side: 32 is the minimum, not the first
        // value refused.
        String exactlyThirtyTwo = "0123456789abcdef0123456789abcdef";

        assertThat(exactlyThirtyTwo.length()).isEqualTo(32);
        assertThatCode(() -> new JwtService(exactlyThirtyTwo, ISSUER, AUDIENCE)).doesNotThrowAnyException();
    }

    // ---------- the happy path still works ----------

    @Test
    void anAcceptableSecretBuildsTheService() {
        assertThatCode(() -> new JwtService(ACCEPTABLE_SECRET, ISSUER, AUDIENCE)).doesNotThrowAnyException();
    }

    // ---------- nothing leaks ----------

    @Test
    void noRefusalMessageEverQuotesTheSecret() {
        // A startup failure is written to a log, and logs are copied around. A
        // message that echoed the value would put the rejected secret - which
        // on a misconfigured deployment may well be a real one from the wrong
        // environment - into that file.
        assertThatThrownBy(() -> new JwtService(RETIRED_PLACEHOLDER, ISSUER, AUDIENCE))
                .hasMessageNotContaining(RETIRED_PLACEHOLDER);

        assertThatThrownBy(() -> new JwtService("far-too-short", ISSUER, AUDIENCE))
                .hasMessageNotContaining("far-too-short");
    }
}
