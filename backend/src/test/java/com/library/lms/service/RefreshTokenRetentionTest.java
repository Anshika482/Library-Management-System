package com.library.lms.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import com.library.lms.config.SchedulingConfig;
import com.library.lms.repository.RefreshTokenRepository;
import com.library.lms.repository.UserRepository;

/**
 * Retention: what a deployment may configure, what the sweep asks the database
 * for, and that it is actually scheduled.
 *
 * <p>No database and no context. A fixed clock makes the cutoff exact, so the
 * arithmetic can be asserted rather than approximated, and the repository is a
 * mock, so what the sweep would delete is visible without deleting anything.
 * Whether the delete removes the right rows is a question about SQL and is
 * answered in {@code RefreshTokenRetentionIntegrationTest}.</p>
 */
class RefreshTokenRetentionTest {

    private static final Duration VALIDITY = Duration.ofDays(7);

    private static final Duration RETENTION = Duration.ofDays(30);

    /** A fixed moment, so "now minus retention" is a value this test can name. */
    private static final Instant NOW = Instant.parse("2026-09-18T06:30:00Z");

    private static final Clock FIXED = Clock.fixed(NOW, ZoneId.of("Asia/Kolkata"));

    private final RefreshTokenRepository tokens = mock(RefreshTokenRepository.class);

    private final UserRepository users = mock(UserRepository.class);

    private RefreshTokenService service(Duration validity, Duration retention) {
        return new RefreshTokenService(tokens, users, validity, retention, FIXED);
    }

    private RefreshTokenService service() {
        return service(VALIDITY, RETENTION);
    }

    // ---------- configuration ----------

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"PT0S", "-PT1H", "-P30D"})
    void aRetentionThatIsNotPositiveStopsStartup(String retention) {
        Duration configured = retention == null ? null : Duration.parse(retention);

        assertThatThrownBy(() -> service(VALIDITY, configured))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(RefreshTokenService.RETENTION_PROPERTY)
                .hasMessageContaining("JWT_REFRESH_TOKEN_RETENTION");
    }

    @ParameterizedTest
    @ValueSource(strings = {"PT1S", "P1D", "P6D", "PT167H"})
    void aRetentionShorterThanASessionStopsStartup(String retention) {
        assertThatThrownBy(() -> service(VALIDITY, Duration.parse(retention)))
                .as("rows would be deleted while the session that owns them is still running")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(RefreshTokenService.RETENTION_PROPERTY)
                .hasMessageContaining(RefreshTokenService.VALIDITY_PROPERTY);
    }

    @Test
    void aRetentionEqualToASessionIsAccepted() {
        assertThatCode(() -> service(VALIDITY, VALIDITY))
                .as("the shortest retention that still covers a whole session")
                .doesNotThrowAnyException();
    }

    @Test
    void theDefaultsShipWithRetentionWellAboveASession() {
        assertThatCode(() -> service(Duration.parse("P7D"), Duration.parse("P30D"))).doesNotThrowAnyException();
    }

    // ---------- the cutoff ----------

    @Test
    void theSweepAsksForSessionsThatEndedBeforeNowMinusRetention() {
        when(tokens.deleteExpiredBefore(org.mockito.ArgumentMatchers.any(), anyInt())).thenReturn(0);

        service().purgeExpiredSessions();

        ArgumentCaptor<LocalDateTime> cutoff = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(tokens).deleteExpiredBefore(cutoff.capture(), eq(RefreshTokenService.PURGE_BATCH_SIZE));

        assertThat(cutoff.getValue()).isEqualTo(LocalDateTime.now(FIXED).minus(RETENTION));
    }

    @Test
    void aLongerRetentionMovesTheCutoffBack() {
        when(tokens.deleteExpiredBefore(org.mockito.ArgumentMatchers.any(), anyInt())).thenReturn(0);

        service(VALIDITY, Duration.ofDays(90)).purgeExpiredSessions();

        ArgumentCaptor<LocalDateTime> cutoff = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(tokens).deleteExpiredBefore(cutoff.capture(), anyInt());

        assertThat(cutoff.getValue()).isEqualTo(LocalDateTime.now(FIXED).minusDays(90));
    }

    // ---------- batching ----------

    @Test
    void aSweepThatFindsNothingDeletesNothingAndAsksOnlyOnce() {
        when(tokens.deleteExpiredBefore(org.mockito.ArgumentMatchers.any(), anyInt())).thenReturn(0);

        assertThat(service().purgeExpiredSessions()).isZero();
        verify(tokens, times(1)).deleteExpiredBefore(org.mockito.ArgumentMatchers.any(), anyInt());
    }

    @Test
    void aPartialBatchEndsTheSweep() {
        when(tokens.deleteExpiredBefore(org.mockito.ArgumentMatchers.any(), anyInt()))
                .thenReturn(RefreshTokenService.PURGE_BATCH_SIZE, 7);

        assertThat(service().purgeExpiredSessions()).isEqualTo(RefreshTokenService.PURGE_BATCH_SIZE + 7);
        verify(tokens, times(2)).deleteExpiredBefore(org.mockito.ArgumentMatchers.any(), anyInt());
    }

    @Test
    void aBacklogIsCappedPerRunRatherThanChasedToTheEnd() {
        when(tokens.deleteExpiredBefore(org.mockito.ArgumentMatchers.any(), anyInt()))
                .thenReturn(RefreshTokenService.PURGE_BATCH_SIZE);

        int removed = service().purgeExpiredSessions();

        assertThat(removed).isEqualTo(RefreshTokenService.PURGE_BATCH_SIZE * RefreshTokenService.PURGE_MAX_BATCHES);
        verify(tokens, times(RefreshTokenService.PURGE_MAX_BATCHES))
                .deleteExpiredBefore(org.mockito.ArgumentMatchers.any(), anyInt());
    }

    @Test
    void theSweepNeverReadsAnAccount() {
        when(tokens.deleteExpiredBefore(org.mockito.ArgumentMatchers.any(), anyInt())).thenReturn(0);

        service().purgeExpiredSessions();

        verifyNoInteractions(users);
    }

    // ---------- that it is scheduled at all ----------

    @Test
    void theSweepIsScheduledOnTheConfiguredInterval() throws Exception {
        Method purge = RefreshTokenService.class.getMethod("purgeExpiredSessions");
        Scheduled scheduled = purge.getAnnotation(Scheduled.class);

        assertThat(scheduled).as("a @Scheduled method, or it would simply never run").isNotNull();
        assertThat(scheduled.fixedDelayString())
                .isEqualTo("${" + RefreshTokenService.CLEANUP_INTERVAL_PROPERTY + "}");
        assertThat(scheduled.initialDelayString()).isEqualTo("PT5M");
        assertThat(scheduled.cron()).as("interval, not cron").isEmpty();
    }

    @Test
    void schedulingIsSwitchedOn() {
        assertThat(SchedulingConfig.class.getAnnotation(EnableScheduling.class))
                .as("without @EnableScheduling a @Scheduled method is never called")
                .isNotNull();
    }
}
