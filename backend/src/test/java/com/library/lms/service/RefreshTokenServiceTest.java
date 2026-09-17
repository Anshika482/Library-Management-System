package com.library.lms.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.library.lms.repository.RefreshTokenRepository;
import com.library.lms.repository.UserRepository;

/**
 * The parts of refresh tokens that need no database: the session lifetime a
 * deployment may configure, how a token is made, and how it is hashed.
 *
 * <p>The flow itself - issuing, rotating, reuse, logout, expiry and account
 * status - is proved against a real database in
 * {@code RefreshTokenIntegrationTest}.</p>
 */
class RefreshTokenServiceTest {

    private final RefreshTokenRepository tokens = mock(RefreshTokenRepository.class);

    private final UserRepository users = mock(UserRepository.class);

    @ParameterizedTest
    @ValueSource(strings = {"PT0S", "-PT1H", "-P7D"})
    void aSessionLifetimeThatIsNotPositiveStopsStartup(String lifetime) {
        assertThatThrownBy(() -> new RefreshTokenService(tokens, users, Duration.parse(lifetime)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(RefreshTokenService.VALIDITY_PROPERTY);
    }

    @Test
    void aMissingSessionLifetimeStopsStartup() {
        assertThatThrownBy(() -> new RefreshTokenService(tokens, users, null))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void tokensAre256RandomBitsInUrlSafeText() {
        RefreshTokenService service = new RefreshTokenService(tokens, users, Duration.ofDays(7));
        Set<String> seen = new HashSet<>();

        for (int i = 0; i < 200; i++) {
            String token = service.newToken();

            assertThat(token).matches("[A-Za-z0-9_-]{43}");
            assertThat(Base64.getUrlDecoder().decode(token)).hasSize(32);
            assertThat(seen.add(token)).as("no token repeats").isTrue();
        }
    }

    @Test
    void theStoredValueIsTheSha256OfTheTokenInLowercaseHex() {
        assertThat(RefreshTokenService.hash("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");

        String token = new RefreshTokenService(tokens, users, Duration.ofDays(7)).newToken();
        assertThat(RefreshTokenService.hash(token))
                .matches("[0-9a-f]{64}")
                .isEqualTo(RefreshTokenService.hash(token))
                .isNotEqualTo(token);
    }
}
