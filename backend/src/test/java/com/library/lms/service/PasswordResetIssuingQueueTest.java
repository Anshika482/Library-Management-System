package com.library.lms.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The background queue that takes the account-dependent work off the request
 * path: it runs what it is given, bounds what it holds, and reports what is
 * left.
 */
class PasswordResetIssuingQueueTest {

    private final PasswordResetIssuingQueue queue = new PasswordResetIssuingQueue(1);

    private final CountDownLatch release = new CountDownLatch(1);

    @AfterEach
    void stop() {
        release.countDown();
        queue.shutdown();
    }

    private Runnable blockedUntilReleased(CountDownLatch started) {
        return () -> {
            started.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        };
    }

    @Test
    void submittingReturnsBeforeTheWorkRuns() throws Exception {
        CountDownLatch started = new CountDownLatch(1);

        assertThat(queue.submit(blockedUntilReleased(started))).isTrue();

        assertThat(started.await(5, TimeUnit.SECONDS)).as("it runs, on another thread").isTrue();
        assertThat(queue.pending()).as("and the caller did not wait for it").isEqualTo(1);
    }

    @Test
    void aFullQueueRefusesInsteadOfGrowing() throws Exception {
        CountDownLatch started = new CountDownLatch(1);

        assertThat(queue.submit(blockedUntilReleased(started))).as("the one worker").isTrue();
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(queue.submit(() -> { })).as("the one waiting place").isTrue();

        assertThat(queue.submit(() -> { })).as("no room left").isFalse();
        assertThat(queue.pending()).as("a refused task is not counted").isEqualTo(2);
    }

    @Test
    void pendingReachesZeroOnlyWhenEveryTaskHasFinished() throws Exception {
        AtomicInteger ran = new AtomicInteger();
        CountDownLatch started = new CountDownLatch(1);

        queue.submit(blockedUntilReleased(started));
        queue.submit(ran::incrementAndGet);
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(queue.pending()).isEqualTo(2);

        release.countDown();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (queue.pending() > 0 && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }

        assertThat(queue.pending()).isZero();
        assertThat(ran).hasValue(1);
    }

    @Test
    void aFailingTaskStillCountsAsFinished() throws Exception {
        queue.submit(() -> {
            throw new IllegalStateException("boom");
        });

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (queue.pending() > 0 && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }

        assertThat(queue.pending()).isZero();
        assertThat(queue.submit(() -> { })).as("and the queue keeps working").isTrue();
    }
}
