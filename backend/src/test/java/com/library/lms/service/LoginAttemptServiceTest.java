package com.library.lms.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Pins the rules {@link LoginAttemptService} enforces: when a username is
 * blocked, when the block lifts, what clears it, and that the map it keeps
 * cannot grow without bound.
 *
 * <p><b>Time is moved, not waited for.</b> The service takes a {@link Clock},
 * so expiry is tested by advancing a fake one. A test that slept for the real
 * block duration would either take fifteen minutes or force the production
 * default down to something meaningless.</p>
 *
 * <p>The limit here is three failures, low enough to read at a glance; the
 * configured default is higher and is exercised by the integration test.</p>
 */
class LoginAttemptServiceTest {

    private static final int MAX_FAILURES = 3;

    private static final Duration BLOCK = Duration.ofMinutes(15);

    private static final String USERNAME = "step144-user";

    private MutableClock clock;

    private LoginAttemptService service;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-01-01T12:00:00Z"));
        service = new LoginAttemptService(MAX_FAILURES, BLOCK, clock);
    }

    private void fail(String username, int times) {
        for (int i = 0; i < times; i++) {
            service.recordFailure(username);
        }
    }

    // ---------- counting ----------

    @Test
    void aUsernameNobodyHasFailedIsNotBlocked() {
        assertThat(service.isBlocked(USERNAME)).isFalse();
    }

    @Test
    void failuresBelowTheLimitDoNotBlock() {
        fail(USERNAME, MAX_FAILURES - 1);

        assertThat(service.isBlocked(USERNAME)).as("room to mistype").isFalse();
    }

    @Test
    void reachingTheLimitBlocks() {
        fail(USERNAME, MAX_FAILURES);

        assertThat(service.isBlocked(USERNAME)).isTrue();
    }

    @Test
    void anUnknownUsernameIsCountedAndBlockedJustLikeARealOne() {
        // The service never asks whether the account exists. If it did, the
        // block would answer that question for an attacker.
        fail("step144-no-such-account", MAX_FAILURES);

        assertThat(service.isBlocked("step144-no-such-account")).isTrue();
    }

    // ---------- expiry ----------

    @Test
    void theBlockLiftsByItself() {
        fail(USERNAME, MAX_FAILURES);
        assertThat(service.isBlocked(USERNAME)).isTrue();

        clock.advance(BLOCK);

        assertThat(service.isBlocked(USERNAME)).as("temporary, never permanent").isFalse();
    }

    @Test
    void aBlockIsStillInPlaceOneSecondBeforeItExpires() {
        fail(USERNAME, MAX_FAILURES);

        clock.advance(BLOCK.minusSeconds(1));

        assertThat(service.isBlocked(USERNAME)).isTrue();
    }

    @Test
    void anIdleCountFadesSoOldMistakesDoNotAccumulate() {
        fail(USERNAME, MAX_FAILURES - 1);
        clock.advance(BLOCK.plusSeconds(1));

        fail(USERNAME, MAX_FAILURES - 1);

        assertThat(service.isBlocked(USERNAME))
                .as("two mistakes today plus two last month is not an attack")
                .isFalse();
    }

    @Test
    void anExpiredEntryIsDroppedWhenItIsNextLookedAt() {
        fail(USERNAME, 1);
        assertThat(service.trackedUsernames()).isEqualTo(1);

        clock.advance(BLOCK.plusSeconds(1));
        service.isBlocked(USERNAME);

        assertThat(service.trackedUsernames()).as("quiet keys clean themselves up").isZero();
    }

    // ---------- what clears it ----------

    @Test
    void aSuccessfulLoginClearsTheCount() {
        fail(USERNAME, MAX_FAILURES - 1);

        service.reset(USERNAME);
        fail(USERNAME, MAX_FAILURES - 1);

        assertThat(service.isBlocked(USERNAME)).isFalse();
        assertThat(service.trackedUsernames()).isEqualTo(1);
    }

    // ---------- one username does not affect another ----------

    @Test
    void blockingOneUsernameLeavesEveryOtherAlone() {
        fail(USERNAME, MAX_FAILURES);

        assertThat(service.isBlocked(USERNAME)).isTrue();
        assertThat(service.isBlocked("step144-somebody-else")).as("no global lock").isFalse();
    }

    @Test
    void countingIsCaseInsensitiveAndIgnoresSurroundingSpace() {
        // The username column is compared case-insensitively by its collation,
        // so varying case must not buy three more guesses.
        service.recordFailure("Step144-User");
        service.recordFailure("  step144-user  ");
        service.recordFailure("STEP144-USER");

        assertThat(service.isBlocked(USERNAME)).isTrue();
    }

    // ---------- bounded memory ----------

    @Test
    void theMapStopsGrowingAtItsCeiling() {
        // Every failure against a fresh random username would otherwise cost a
        // permanent map entry, turning the defence into a way to exhaust memory.
        for (int i = 0; i < LoginAttemptService.MAX_TRACKED_USERNAMES + 500; i++) {
            service.recordFailure("step144-flood-" + i);
        }

        assertThat(service.trackedUsernames())
                .isLessThanOrEqualTo(LoginAttemptService.MAX_TRACKED_USERNAMES);
    }

    @Test
    void expiredEntriesAreSweptAwayOnceTheMapIsBusy() {
        for (int i = 0; i < LoginAttemptService.MAX_TRACKED_USERNAMES; i++) {
            service.recordFailure("step144-old-" + i);
        }

        clock.advance(BLOCK.plusSeconds(1));
        service.recordFailure("step144-after-the-sweep");

        assertThat(service.trackedUsernames())
                .as("the sweep clears what has expired rather than refusing new keys forever")
                .isEqualTo(1);
    }

    @Test
    void aVeryLongUsernameCannotBloatTheKey() {
        service.recordFailure("x".repeat(10_000));

        assertThat(service.trackedUsernames()).isEqualTo(1);
    }

    /** A clock whose instant the test moves by hand. */
    private static final class MutableClock extends Clock {

        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration amount) {
            instant = instant.plus(amount);
        }

        @Override
        public Instant instant() {
            return instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
