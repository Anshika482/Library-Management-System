package com.library.lms.service;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;

/**
 * Runs password reset issuing after the request has been answered.
 *
 * <p><b>Why a queue at all.</b> Whether an address has an account decides how
 * much work a reset request does: none for an unknown address, a lookup, a
 * write and an event for a known one. Done on the request thread, that
 * difference is in the response time, and the response time says whether the
 * address is registered. Here it is done after the answer has gone, on one
 * background thread, so every accepted request costs the caller the same: the
 * rate-limit check and a hand-off to this queue. Nothing sleeps to pad the time
 * out; the variable work is simply no longer in it.</p>
 *
 * <p><b>Bounded.</b> One worker and {@value #CAPACITY} waiting requests. A
 * request that finds the queue full is dropped and answered like any other -
 * the same outcome as the rate limit, and one a flood cannot turn into
 * unbounded memory.</p>
 *
 * <p><b>Deliberately not an {@code Executor} bean.</b> The pool is private to
 * this component, so registering it does not change which executor Spring Boot
 * sets up for the rest of the application. It is shut down with the context,
 * letting queued requests finish for a few seconds first.</p>
 */
@Component
public class PasswordResetIssuingQueue {

    static final int CAPACITY = 500;

    private static final long SHUTDOWN_GRACE_SECONDS = 10;

    private static final Logger log = LoggerFactory.getLogger(PasswordResetIssuingQueue.class);

    private final ThreadPoolExecutor executor;

    private final AtomicInteger pending = new AtomicInteger();

    public PasswordResetIssuingQueue() {
        this(CAPACITY);
    }

    PasswordResetIssuingQueue(int capacity) {
        this.executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(capacity),
                task -> {
                    Thread thread = new Thread(task, "password-reset-issuer");
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }

    /**
     * Queues a task to run after the caller has moved on.
     *
     * @param task the work to run
     * @return true if it was queued, false if the queue was full and it was
     *         dropped
     */
    public boolean submit(Runnable task) {
        pending.incrementAndGet();

        try {
            executor.execute(() -> {
                try {
                    task.run();
                } finally {
                    pending.decrementAndGet();
                }
            });
            return true;
        } catch (RejectedExecutionException rejected) {
            pending.decrementAndGet();
            return false;
        }
    }

    /**
     * How many queued tasks have not yet finished - zero only once every task
     * submitted so far has run to the end. For monitoring and for tests.
     */
    public int pending() {
        return pending.get();
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();

        try {
            if (!executor.awaitTermination(SHUTDOWN_GRACE_SECONDS, TimeUnit.SECONDS)) {
                log.warn("Password reset issuing did not finish within {}s of shutdown; {} request(s) abandoned",
                        SHUTDOWN_GRACE_SECONDS, executor.getQueue().size());
                executor.shutdownNow();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }
}
