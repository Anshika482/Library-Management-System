package com.library.lms.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * The {@link PasswordResetRequestLimiter} the application runs with: counters
 * in memory, per instance.
 *
 * <p><b>The same shape as {@link LoginAttemptService}:</b> counters in memory,
 * keyed by the normalised address, bounded in number, and on an injectable
 * clock. Each address may ask {@code security.password-reset.max-requests}
 * times in a {@code security.password-reset.request-window}; after that its
 * requests are dropped until the window has passed.</p>
 *
 * <p><b>Counted before anything is looked up</b>, so an address with no account
 * uses up its allowance exactly as a real one does. A dropped request is still
 * answered with the same 202 - the limit stops a mailbox being flooded and the
 * token table being filled, and says nothing about whether the address is
 * known.</p>
 *
 * <p>Like the login limit, it is per instance and forgotten on restart. If the
 * table ever fills, new addresses are let through untracked rather than
 * refused, as the login limit does: refusing would let anyone stop every reset
 * by filling the table.</p>
 */
@Service
public class InMemoryPasswordResetRequestLimiter implements PasswordResetRequestLimiter {

    private static final Logger log = LoggerFactory.getLogger(InMemoryPasswordResetRequestLimiter.class);

    static final int MAX_TRACKED_ADDRESSES = 10_000;

    private static final int PURGE_THRESHOLD = MAX_TRACKED_ADDRESSES / 2;

    private static final int MAX_KEY_LENGTH = 255;

    private final int maxRequests;

    private final Duration window;

    private final Clock clock;

    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    @Autowired
    public InMemoryPasswordResetRequestLimiter(
            @Value("${security.password-reset.max-requests}") int maxRequests,
            @Value("${security.password-reset.request-window}") Duration window) {
        this(maxRequests, window, Clock.systemUTC());
    }

    InMemoryPasswordResetRequestLimiter(int maxRequests, Duration window, Clock clock) {
        if (maxRequests < 1) {
            throw new IllegalStateException("security.password-reset.max-requests must be at least 1");
        }
        if (window == null || window.isZero() || window.isNegative()) {
            throw new IllegalStateException("security.password-reset.request-window must be a positive duration");
        }

        this.maxRequests = maxRequests;
        this.window = window;
        this.clock = clock;
    }

    /** Requests counted in the current window, and when that window began. */
    private record Window(int requests, Instant startedAt) {

        boolean isExpiredAt(Instant now, Duration length) {
            return !now.isBefore(startedAt.plus(length));
        }
    }

    /**
     * Counts a request for this address and says whether it may go ahead.
     *
     * @param email the address, as submitted; compared trimmed and lower-cased
     * @return true if the request is within the limit
     */
    @Override
    public boolean tryAcquire(String email) {
        String key = key(email);
        Instant now = clock.instant();

        if (windows.size() >= PURGE_THRESHOLD) {
            purgeExpired(now);
        }

        boolean[] allowed = {true};

        windows.compute(key, (ignored, current) -> {
            if (current == null || current.isExpiredAt(now, window)) {
                if (current == null && windows.size() >= MAX_TRACKED_ADDRESSES) {
                    log.debug("Password reset request tracking is full; not tracking another address");
                    return null;
                }
                return new Window(1, now);
            }

            if (current.requests() >= maxRequests) {
                allowed[0] = false;
                return current;
            }

            return new Window(current.requests() + 1, current.startedAt());
        });

        return allowed[0];
    }

    /** How many addresses are currently tracked; for tests. */
    int trackedAddresses() {
        return windows.size();
    }

    private void purgeExpired(Instant now) {
        windows.entrySet().removeIf(entry -> entry.getValue().isExpiredAt(now, window));
    }

    private static String key(String email) {
        if (email == null) {
            return "";
        }

        String normalised = email.trim().toLowerCase(Locale.ROOT);

        return normalised.length() <= MAX_KEY_LENGTH ? normalised : normalised.substring(0, MAX_KEY_LENGTH);
    }
}
