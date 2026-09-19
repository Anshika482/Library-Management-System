package com.library.lms.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.library.lms.entity.PasswordResetToken;
import com.library.lms.entity.Role;
import com.library.lms.entity.User;
import com.library.lms.exception.InvalidPasswordResetTokenException;
import com.library.lms.repository.PasswordResetTokenRepository;
import com.library.lms.repository.UserRepository;

/**
 * The self-service reset's request path, redemption and sweep, with no
 * database and a fixed clock.
 *
 * <p><b>The request path is the timing boundary.</b> Whatever the address, a
 * request may do two things on the caller's thread: ask the rate limit, and
 * hand work to the issuing queue. These tests hold the queued work back and
 * check that nothing else happened - no account lookup, no token, no write - so
 * an address with an account cannot cost the caller more time than one
 * without.</p>
 */
class SelfServicePasswordResetServiceTest {

    private static final Duration RETENTION = Duration.ofDays(1);

    private static final Clock FIXED = Clock.fixed(Instant.parse("2026-09-19T06:30:00Z"), ZoneId.of("Asia/Kolkata"));

    private static final LocalDateTime NOW = LocalDateTime.now(FIXED);

    private static final String EMAIL = "reader@example.invalid";

    private final PasswordResetRequestLimiter limiter = mock(PasswordResetRequestLimiter.class);

    private final PasswordResetIssuingQueue queue = mock(PasswordResetIssuingQueue.class);

    private final PasswordResetTokenIssuer issuer = mock(PasswordResetTokenIssuer.class);

    private final PasswordResetTokenRepository tokens = mock(PasswordResetTokenRepository.class);

    private final UserRepository users = mock(UserRepository.class);

    private final PasswordEncoder encoder = mock(PasswordEncoder.class);

    private final RefreshTokenService refreshTokens = mock(RefreshTokenService.class);

    private final LoginAttemptService loginAttempts = mock(LoginAttemptService.class);

    /** Work handed to the queue, held back so the test decides when - and whether - it runs. */
    private final List<Runnable> queued = new ArrayList<>();

    private SelfServicePasswordResetService service(Duration retention) {
        return new SelfServicePasswordResetService(limiter, queue, issuer, tokens, users, encoder, refreshTokens,
                loginAttempts, retention, FIXED);
    }

    private final SelfServicePasswordResetService service = service(RETENTION);

    private void queueAcceptsAndHoldsWork() {
        when(queue.submit(any())).thenAnswer(invocation -> queued.add(invocation.getArgument(0)));
    }

    private static User account(boolean enabled, boolean accountNonLocked) {
        User user = new User();
        user.setId(42L);
        user.setUsername("reader");
        user.setEmail(EMAIL);
        user.setRole(Role.ROLE_MEMBER);
        user.setEnabled(enabled);
        user.setAccountNonLocked(accountNonLocked);
        user.setPassword("$2a$10$old-hash");
        return user;
    }

    private PasswordResetToken stored(User user, LocalDateTime expiresAt, LocalDateTime usedAt) {
        PasswordResetToken token = new PasswordResetToken();
        token.setUser(user);
        token.setTokenHash(PasswordResetTokenIssuer.hash("presented-token"));
        token.setExpiresAt(expiresAt);
        token.setUsedAt(usedAt);
        when(tokens.findByTokenHash(PasswordResetTokenIssuer.hash("presented-token"))).thenReturn(Optional.of(token));
        return token;
    }

    private void assertNothingRedeemed() {
        verify(encoder, never()).encode(anyString());
        verify(users, never()).save(any());
        verifyNoInteractions(refreshTokens, loginAttempts);
    }

    // ---------- configuration ----------

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"PT0S", "-PT1H", "-P1D"})
    void aRetentionThatIsNotPositiveStopsStartup(String retention) {
        Duration configured = retention == null ? null : Duration.parse(retention);

        assertThatThrownBy(() -> service(configured))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(SelfServicePasswordResetService.RETENTION_PROPERTY)
                .hasMessageContaining("PASSWORD_RESET_TOKEN_RETENTION");
    }

    @Test
    void aShortRetentionIsAccepted() {
        assertThatCode(() -> service(Duration.ofMinutes(1))).doesNotThrowAnyException();
    }

    // ---------- the request path: the same work whatever the address ----------

    @Test
    void aRequestOnlyAsksTheLimitAndQueuesTheWorkBeforeAnswering() {
        when(limiter.tryAcquire(EMAIL)).thenReturn(true);
        queueAcceptsAndHoldsWork();

        service.requestReset(EMAIL);

        assertThat(queued).as("exactly one piece of work queued").hasSize(1);
        verifyNoInteractions(issuer, users, tokens);
    }

    @Test
    void anAddressWithAnAccountAndOneWithoutCostTheCallerTheSameSteps() {
        when(limiter.tryAcquire(anyString())).thenReturn(true);
        queueAcceptsAndHoldsWork();

        service.requestReset(EMAIL);
        service.requestReset("nobody@example.invalid");

        assertThat(queued).hasSize(2);
        verify(limiter).tryAcquire(EMAIL);
        verify(limiter).tryAcquire("nobody@example.invalid");
        verify(queue, times(2)).submit(any());
        verifyNoInteractions(issuer, users, tokens);
    }

    @Test
    void theQueuedWorkIssuesForTheAddressThatWasAsked() {
        when(limiter.tryAcquire(EMAIL)).thenReturn(true);
        queueAcceptsAndHoldsWork();

        service.requestReset(EMAIL);
        queued.get(0).run();

        verify(issuer).issueFor(EMAIL);
    }

    @Test
    void aRateLimitedRequestQueuesNothing() {
        when(limiter.tryAcquire(EMAIL)).thenReturn(false);

        service.requestReset(EMAIL);

        verifyNoInteractions(queue, issuer, users, tokens);
    }

    @Test
    void aFullQueueDropsTheRequestWithoutAnError() {
        when(limiter.tryAcquire(EMAIL)).thenReturn(true);
        when(queue.submit(any())).thenReturn(false);

        assertThatCode(() -> service.requestReset(EMAIL))
                .as("answered like any other request")
                .doesNotThrowAnyException();
        verifyNoInteractions(issuer);
    }

    @Test
    void aFailureWhileIssuingDoesNotEscapeTheQueue() {
        when(limiter.tryAcquire(EMAIL)).thenReturn(true);
        queueAcceptsAndHoldsWork();
        doThrow(new IllegalStateException("database unavailable for " + EMAIL)).when(issuer).issueFor(EMAIL);

        service.requestReset(EMAIL);

        assertThatCode(() -> queued.get(0).run()).doesNotThrowAnyException();
    }

    @Test
    void theServiceDependsOnlyOnTheLimiterInterface() {
        assertThat(PasswordResetRequestLimiter.class).isInterface();
        assertThat(InMemoryPasswordResetRequestLimiter.class).isAssignableTo(PasswordResetRequestLimiter.class);
    }

    // ---------- redemptions that change nothing ----------

    @Test
    void anUnknownTokenIsRefused() {
        when(tokens.findByTokenHash(anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resetPassword("presented-token", "new-password"))
                .isInstanceOf(InvalidPasswordResetTokenException.class);

        assertNothingRedeemed();
    }

    @Test
    void aUsedTokenIsRefused() {
        stored(account(true, true), NOW.plusMinutes(10), NOW.minusMinutes(1));

        assertThatThrownBy(() -> service.resetPassword("presented-token", "new-password"))
                .isInstanceOf(InvalidPasswordResetTokenException.class);

        assertNothingRedeemed();
    }

    @Test
    void aTokenIsRefusedFromTheMomentItExpires() {
        stored(account(true, true), NOW, null);

        assertThatThrownBy(() -> service.resetPassword("presented-token", "new-password"))
                .as("expiring exactly now is expired")
                .isInstanceOf(InvalidPasswordResetTokenException.class);

        assertNothingRedeemed();
    }

    @Test
    void aTokenOfAnAccountSinceDisabledOrLockedIsRefused() {
        stored(account(false, true), NOW.plusMinutes(10), null);
        assertThatThrownBy(() -> service.resetPassword("presented-token", "new-password"))
                .isInstanceOf(InvalidPasswordResetTokenException.class);

        stored(account(true, false), NOW.plusMinutes(10), null);
        assertThatThrownBy(() -> service.resetPassword("presented-token", "new-password"))
                .isInstanceOf(InvalidPasswordResetTokenException.class);

        assertNothingRedeemed();
    }

    @Test
    void everyRefusalIsTheSameSentence() {
        assertThat(new InvalidPasswordResetTokenException().getMessage())
                .isEqualTo("Invalid or expired password reset token.");
    }

    // ---------- a redemption that goes through ----------

    @Test
    void aRedemptionSpendsTheTokenAndEndsEverySession() {
        User user = account(true, true);
        PasswordResetToken presented = stored(user, NOW.plusSeconds(1), null);
        when(encoder.encode("new-password")).thenReturn("$2a$10$new-hash");

        service.resetPassword("presented-token", "new-password");

        assertThat(presented.getUsedAt()).as("spent: it cannot be presented again").isEqualTo(NOW);
        assertThat(user.getPassword()).as("the encoder's output, never the password").isEqualTo("$2a$10$new-hash");

        InOrder order = inOrder(issuer, encoder, users, refreshTokens, loginAttempts);
        order.verify(issuer).spendOutstanding(user, NOW);
        order.verify(encoder).encode("new-password");
        order.verify(users).save(user);
        order.verify(refreshTokens).revokeAllFor(user);
        order.verify(loginAttempts).reset("reader");
    }

    // ---------- the sweep ----------

    @Test
    void theSweepRemovesTokensThatStoppedWorkingBeforeNowMinusRetention() {
        when(tokens.deleteSpentBefore(any(), anyInt())).thenReturn(0);

        service.purgeSpentTokens();

        ArgumentCaptor<LocalDateTime> cutoff = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(tokens).deleteSpentBefore(cutoff.capture(), eq(SelfServicePasswordResetService.PURGE_BATCH_SIZE));
        assertThat(cutoff.getValue()).isEqualTo(NOW.minus(RETENTION));
    }

    @Test
    void aPartialBatchEndsTheSweep() {
        when(tokens.deleteSpentBefore(any(), anyInt()))
                .thenReturn(SelfServicePasswordResetService.PURGE_BATCH_SIZE, 3);

        assertThat(service.purgeSpentTokens()).isEqualTo(SelfServicePasswordResetService.PURGE_BATCH_SIZE + 3);
        verify(tokens, times(2)).deleteSpentBefore(any(), anyInt());
    }

    @Test
    void aBacklogIsCappedPerRunRatherThanChasedToTheEnd() {
        when(tokens.deleteSpentBefore(any(), anyInt())).thenReturn(SelfServicePasswordResetService.PURGE_BATCH_SIZE);

        assertThat(service.purgeSpentTokens()).isEqualTo(
                SelfServicePasswordResetService.PURGE_BATCH_SIZE * SelfServicePasswordResetService.PURGE_MAX_BATCHES);
        verify(tokens, times(SelfServicePasswordResetService.PURGE_MAX_BATCHES)).deleteSpentBefore(any(), anyInt());
    }

    @Test
    void theSweepIsScheduledOnTheConfiguredInterval() throws Exception {
        Method purge = SelfServicePasswordResetService.class.getMethod("purgeSpentTokens");
        Scheduled scheduled = purge.getAnnotation(Scheduled.class);

        assertThat(scheduled).as("a @Scheduled method, or it would never run").isNotNull();
        assertThat(scheduled.fixedDelayString())
                .isEqualTo("${" + SelfServicePasswordResetService.CLEANUP_INTERVAL_PROPERTY + "}");
        assertThat(scheduled.initialDelayString()).isEqualTo("PT5M");
    }
}
