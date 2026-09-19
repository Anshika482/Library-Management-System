package com.library.lms.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import com.library.lms.entity.Library;
import com.library.lms.entity.PasswordResetToken;
import com.library.lms.entity.Role;
import com.library.lms.entity.User;
import com.library.lms.repository.PasswordResetTokenRepository;
import com.library.lms.repository.UserRepository;

/**
 * Issuing a reset token - the part of a request that depends on whether the
 * address has an account - with no database and a fixed clock.
 */
class PasswordResetTokenIssuerTest {

    private static final Duration VALIDITY = Duration.ofMinutes(30);

    private static final Clock FIXED = Clock.fixed(Instant.parse("2026-09-19T06:30:00Z"), ZoneId.of("Asia/Kolkata"));

    private static final LocalDateTime NOW = LocalDateTime.now(FIXED);

    private static final String EMAIL = "reader@example.invalid";

    private final PasswordResetTokenRepository tokens = mock(PasswordResetTokenRepository.class);

    private final UserRepository users = mock(UserRepository.class);

    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);

    private PasswordResetTokenIssuer issuer(Duration validity) {
        return new PasswordResetTokenIssuer(tokens, users, events, mock(AuditService.class), validity, FIXED);
    }

    private final PasswordResetTokenIssuer issuer = issuer(VALIDITY);

    private static User account(boolean enabled, boolean accountNonLocked) {
        Library library = new Library();
        library.setId(7L);

        User user = new User();
        user.setId(42L);
        user.setLibrary(library);
        user.setUsername("reader");
        user.setEmail(EMAIL);
        user.setRole(Role.ROLE_MEMBER);
        user.setEnabled(enabled);
        user.setAccountNonLocked(accountNonLocked);
        return user;
    }

    private void assertNothingIssued() {
        verify(tokens, never()).save(any());
        verifyNoInteractions(events);
    }

    // ---------- configuration ----------

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"PT0S", "-PT1M", "PT24H1S", "P2D"})
    void aTokenLifetimeThatIsNotPositiveOrIsOverADayStopsStartup(String lifetime) {
        Duration configured = lifetime == null ? null : Duration.parse(lifetime);

        assertThatThrownBy(() -> issuer(configured))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(PasswordResetTokenIssuer.VALIDITY_PROPERTY)
                .hasMessageContaining("PASSWORD_RESET_TOKEN_VALIDITY");
    }

    @Test
    void lifetimesUpToADayAreAccepted() {
        assertThatCode(() -> issuer(Duration.ofMinutes(1))).doesNotThrowAnyException();
        assertThatCode(() -> issuer(Duration.ofHours(24))).doesNotThrowAnyException();
    }

    // ---------- addresses that are issued nothing ----------

    @Test
    void anAddressWithNoAccountIsIssuedNothing() {
        when(users.findByEmail(EMAIL)).thenReturn(Optional.empty());

        issuer.issueFor(EMAIL);

        assertNothingIssued();
    }

    @Test
    void aDisabledOrLockedAccountIsIssuedNothing() {
        when(users.findByEmail(EMAIL)).thenReturn(Optional.of(account(false, true)));
        issuer.issueFor(EMAIL);

        when(users.findByEmail(EMAIL)).thenReturn(Optional.of(account(true, false)));
        issuer.issueFor(EMAIL);

        assertNothingIssued();
    }

    // ---------- an issued token ----------

    @Test
    void anIssuedTokenIsStoredOnlyAsItsHashAndExpiresOnTime() {
        when(users.findByEmail(EMAIL)).thenReturn(Optional.of(account(true, true)));
        when(tokens.findByUserIdAndUsedAtIsNull(42L)).thenReturn(List.of());

        issuer.issueFor("  " + EMAIL + " ");

        ArgumentCaptor<PasswordResetToken> saved = ArgumentCaptor.forClass(PasswordResetToken.class);
        verify(tokens).save(saved.capture());
        ArgumentCaptor<PasswordResetRequested> published = ArgumentCaptor.forClass(PasswordResetRequested.class);
        verify(events).publishEvent(published.capture());

        String token = published.getValue().token();

        assertThat(token).as("256 bits, URL-safe").hasSize(43).matches("[A-Za-z0-9_-]+");
        assertThat(saved.getValue().getTokenHash())
                .as("the SHA-256 of the token, never the token")
                .isEqualTo(PasswordResetTokenIssuer.hash(token))
                .isNotEqualTo(token)
                .hasSize(64);
        assertThat(saved.getValue().getCreatedAt()).isEqualTo(NOW);
        assertThat(saved.getValue().getExpiresAt()).isEqualTo(NOW.plus(VALIDITY));
        assertThat(saved.getValue().getUsedAt()).isNull();

        assertThat(published.getValue().userId()).isEqualTo(42L);
        assertThat(published.getValue().email()).isEqualTo(EMAIL);
        assertThat(published.getValue().expiresAt()).isEqualTo(NOW.plus(VALIDITY));
    }

    @Test
    void issuingSpendsEveryEarlierUnusedToken() {
        User user = account(true, true);
        PasswordResetToken earlier = new PasswordResetToken();
        earlier.setUser(user);
        when(users.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(tokens.findByUserIdAndUsedAtIsNull(42L)).thenReturn(List.of(earlier));

        issuer.issueFor(EMAIL);

        assertThat(earlier.getUsedAt()).as("superseded the moment a newer one exists").isEqualTo(NOW);
    }

    @Test
    void theEventNeverPrintsTheTokenOrTheAddress() {
        PasswordResetRequested event = new PasswordResetRequested(42L, EMAIL, "the-raw-token", NOW);

        assertThat(event.toString()).doesNotContain("the-raw-token").doesNotContain(EMAIL).contains("42");
    }

    @Test
    void tokensDoNotRepeat() {
        assertThat(issuer.newToken()).isNotEqualTo(issuer.newToken());
    }
}
