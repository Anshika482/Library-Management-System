package com.library.lms.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

/**
 * The in-memory, per-instance limit on password reset requests, on a clock the
 * test moves.
 */
class InMemoryPasswordResetRequestLimiterTest {

    private static final Duration WINDOW = Duration.ofMinutes(15);

    /** A clock that stands still until the test moves it. */
    private static final class MovableClock extends Clock {

        private Instant now = Instant.parse("2026-09-19T06:00:00Z");

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private final MovableClock clock = new MovableClock();

    private final InMemoryPasswordResetRequestLimiter limiter =
            new InMemoryPasswordResetRequestLimiter(3, WINDOW, clock);

    @Test
    void anAddressMayAskUpToTheLimitWithinAWindow() {
        assertThat(limiter.tryAcquire("reader@example.invalid")).isTrue();
        assertThat(limiter.tryAcquire("reader@example.invalid")).isTrue();
        assertThat(limiter.tryAcquire("reader@example.invalid")).isTrue();
        assertThat(limiter.tryAcquire("reader@example.invalid")).as("the fourth in the window").isFalse();
        assertThat(limiter.tryAcquire("reader@example.invalid")).as("and every one after it").isFalse();
    }

    @Test
    void theAllowanceComesBackOnceTheWindowHasPassed() {
        for (int i = 0; i < 3; i++) {
            limiter.tryAcquire("reader@example.invalid");
        }

        clock.advance(WINDOW.minusSeconds(1));
        assertThat(limiter.tryAcquire("reader@example.invalid")).as("a second before the window ends").isFalse();

        clock.advance(Duration.ofSeconds(1));
        assertThat(limiter.tryAcquire("reader@example.invalid")).as("once it has ended").isTrue();
    }

    @Test
    void anAddressIsTheSameWhateverItsCaseOrSurroundingSpaces() {
        limiter.tryAcquire("Reader@Example.invalid");
        limiter.tryAcquire("  reader@example.INVALID ");
        limiter.tryAcquire("READER@EXAMPLE.INVALID");

        assertThat(limiter.tryAcquire("reader@example.invalid")).isFalse();
    }

    @Test
    void addressesAreLimitedIndependently() {
        for (int i = 0; i < 3; i++) {
            limiter.tryAcquire("first@example.invalid");
        }

        assertThat(limiter.tryAcquire("first@example.invalid")).isFalse();
        assertThat(limiter.tryAcquire("second@example.invalid")).as("another address is unaffected").isTrue();
    }

    @Test
    void expiredWindowsAreForgottenAsNewAddressesArrive() {
        for (int i = 0; i < InMemoryPasswordResetRequestLimiter.MAX_TRACKED_ADDRESSES / 2; i++) {
            limiter.tryAcquire("address-" + i + "@example.invalid");
        }
        clock.advance(WINDOW);

        limiter.tryAcquire("one-more@example.invalid");

        assertThat(limiter.trackedAddresses()).as("the expired windows were purged").isEqualTo(1);
    }

    @Test
    void itIsTheImplementationBehindTheReplaceableBoundary() {
        assertThat(limiter).isInstanceOf(PasswordResetRequestLimiter.class);
    }

    @Test
    void anUnusableConfigurationStopsStartup() {
        assertThatThrownBy(() -> new InMemoryPasswordResetRequestLimiter(0, WINDOW, clock))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("max-requests");
        assertThatThrownBy(() -> new InMemoryPasswordResetRequestLimiter(3, Duration.ZERO, clock))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("request-window");
        assertThatThrownBy(() -> new InMemoryPasswordResetRequestLimiter(3, null, clock))
                .isInstanceOf(IllegalStateException.class);
    }
}
