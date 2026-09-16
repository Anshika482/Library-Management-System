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
 * Counts failed logins per username and refuses further attempts for a while
 * once there have been too many.
 *
 * <p>Without this, {@code POST /api/auth/login} will answer an unlimited number
 * of guesses as fast as BCrypt can check them. BCrypt is deliberately slow,
 * which raises the cost of a guess but does not cap the number of them - and
 * that same slowness makes the endpoint an amplifier, since every guess costs
 * the server far more than it costs the caller.</p>
 *
 * <p><b>Single instance, in memory, on purpose.</b> The counters live in one
 * {@link ConcurrentHashMap} in this JVM. That is the right size for this
 * project, which runs as one process, and it needs no Redis, no extra table and
 * no schema change. It is also the limit of what this class can promise: run
 * two instances behind a load balancer and each keeps its own counters, so the
 * effective limit multiplies by the number of instances, and a restart forgets
 * everything. <b>A distributed deployment needs a shared rate limiter</b> - the
 * three methods below are the entire surface to reimplement against Redis or a
 * gateway.</p>
 *
 * <p><b>Keyed by username, not by address.</b> Blocking the submitted username
 * stops the attack this is meant to stop: many guesses against one account.
 * Client addresses were considered and left out - behind a proxy or NAT the
 * address is either everyone's or trivially rotated, and the only honest source
 * for it would be a forwarding header, which a caller can set to anything
 * unless the application is told which proxies to trust. This application has
 * no such configuration, so no header is read here. The consequence is stated
 * plainly: a spray of one guess each across <i>many</i> usernames is not
 * stopped by this class, and wants an address-aware or global limiter in front
 * of the service.</p>
 *
 * <p><b>It cannot be used to find out who exists.</b> The key is whatever
 * username was submitted, looked up in no database; an account that does not
 * exist is counted and blocked exactly like one that does. The caller is told
 * the same thing either way, because a block raises an ordinary authentication
 * failure and the API answers every one of those with one fixed sentence.</p>
 *
 * <p><b>A note on what blocking costs.</b> Anyone who knows a username can keep
 * it blocked by failing on purpose. That is the accepted trade of per-account
 * throttling: a temporary, self-clearing block is a smaller harm than an
 * unlimited guess rate, and this deliberately never disables an account
 * permanently - nothing here writes to the database at all.</p>
 */
@Service
public class LoginAttemptService {

    private static final Logger log = LoggerFactory.getLogger(LoginAttemptService.class);

    /**
     * How many usernames may be tracked at once.
     *
     * <p>The map is keyed by whatever was submitted, so without a ceiling a
     * caller could mint unlimited keys - each failed guess against a fresh
     * random name costing the server a map entry - and turn a defence into a
     * way to exhaust memory.</p>
     */
    static final int MAX_TRACKED_USERNAMES = 10_000;

    /** Above this, a sweep of expired entries runs before anything new is added. */
    private static final int PURGE_THRESHOLD = MAX_TRACKED_USERNAMES / 2;

    /** Longest key kept, so one enormous username cannot bloat the map. */
    private static final int MAX_KEY_LENGTH = 255;

    private final int maxFailedAttempts;

    private final Duration blockDuration;

    private final Clock clock;

    /**
     * Failure state per username.
     *
     * <p>{@code ConcurrentHashMap} rather than a synchronized map, and every
     * update goes through {@code compute}, which locks one bin rather than the
     * whole table. One username being attacked therefore never delays anyone
     * else's login.</p>
     */
    private final ConcurrentHashMap<String, Attempts> attempts = new ConcurrentHashMap<>();

    @Autowired
    public LoginAttemptService(
            @Value("${security.login.max-failed-attempts}") int maxFailedAttempts,
            @Value("${security.login.block-duration}") Duration blockDuration) {
        this(maxFailedAttempts, blockDuration, Clock.systemUTC());
    }

    /**
     * Takes the clock, so expiry can be tested by moving time rather than by
     * waiting for it. Package-private: only the test in this package builds a
     * service this way.
     */
    LoginAttemptService(int maxFailedAttempts, Duration blockDuration, Clock clock) {
        this.maxFailedAttempts = maxFailedAttempts;
        this.blockDuration = blockDuration;
        this.clock = clock;
    }

    /**
     * What is known about one username's recent failures.
     *
     * <p>Immutable, and always replaced wholesale inside {@code compute}, so a
     * reader can never see a half-updated count.</p>
     *
     * @param failures      consecutive failures counted so far
     * @param lastFailureAt when the most recent one happened
     * @param blockedUntil  when the block lifts, or {@code null} if not blocked
     */
    private record Attempts(int failures, Instant lastFailureAt, Instant blockedUntil) {

        boolean isBlockedAt(Instant now) {
            return blockedUntil != null && now.isBefore(blockedUntil);
        }

        /**
         * Whether this entry no longer says anything useful.
         *
         * <p>A blocked entry lives until its block lifts. An entry that is only
         * counting lives for one block duration after its last failure, so a
         * mistake today cannot combine with a mistake next week to lock someone
         * out.</p>
         */
        boolean isExpiredAt(Instant now, Duration window) {
            Instant expiry = blockedUntil != null ? blockedUntil : lastFailureAt.plus(window);

            return !now.isBefore(expiry);
        }
    }

    /**
     * Whether logins for this username are currently refused.
     *
     * <p>Checked before the password is verified, which is the point: a blocked
     * attempt costs no BCrypt work. An entry found expired is dropped here, so
     * quiet keys clean themselves up as they are touched, without a scheduler.</p>
     *
     * @param username the submitted username, existing or not
     * @return true while the block lasts
     */
    public boolean isBlocked(String username) {
        String key = key(username);
        Attempts current = attempts.get(key);

        if (current == null) {
            return false;
        }

        Instant now = clock.instant();
        if (current.isBlockedAt(now)) {
            return true;
        }

        if (current.isExpiredAt(now, blockDuration)) {
            attempts.remove(key, current);
        }

        return false;
    }

    /**
     * Counts one failed attempt, blocking the username when the limit is
     * reached.
     *
     * <p>Called for every rejected login, whether or not the account exists -
     * treating the two differently is exactly the side channel this must not
     * open.</p>
     *
     * @param username the username that was submitted
     */
    public void recordFailure(String username) {
        String key = key(username);
        Instant now = clock.instant();

        if (attempts.size() >= PURGE_THRESHOLD) {
            purgeExpired(now);
        }

        attempts.compute(key, (ignored, current) -> {
            if (current == null || current.isExpiredAt(now, blockDuration)) {
                // A new or long-idle username. Refuse to add it if the map is
                // full: dropping the count leaves this one username unprotected,
                // where refusing logins instead would turn a full map into an
                // outage for everybody.
                if (current == null && attempts.size() >= MAX_TRACKED_USERNAMES) {
                    log.debug("Login attempt tracking is full; not tracking another username");
                    return null;
                }

                return new Attempts(1, now, null);
            }

            int failures = current.failures() + 1;
            Instant blockedUntil = failures >= maxFailedAttempts ? now.plus(blockDuration) : null;

            if (blockedUntil != null && current.blockedUntil() == null) {
                log.warn("Login temporarily blocked after {} failed attempts", failures);
            }

            return new Attempts(failures, now, blockedUntil);
        });
    }

    /**
     * Forgets this username's failures, called when a login succeeds.
     *
     * <p>Proving the password is the strongest possible evidence that the
     * earlier failures were someone mistyping rather than someone guessing.</p>
     *
     * @param username the username that just authenticated
     */
    public void reset(String username) {
        attempts.remove(key(username));
    }

    /** How many usernames are currently tracked. For tests and diagnostics. */
    int trackedUsernames() {
        return attempts.size();
    }

    /**
     * Drops every entry whose block has lifted or whose count has gone stale.
     *
     * <p>{@code removeIf} on the entry set walks without locking the whole map,
     * so a sweep does not stop logins happening at the same time.</p>
     */
    private void purgeExpired(Instant now) {
        attempts.entrySet().removeIf(entry -> entry.getValue().isExpiredAt(now, blockDuration));
    }

    /**
     * The map key for a submitted username.
     *
     * <p>Lower-cased and trimmed because the {@code users.username} column is
     * compared case-insensitively by its collation: without this, {@code Alice}
     * and {@code alice} are one account to the database but two counters here,
     * and the limit could be walked straight past by changing case. Truncated
     * so a caller cannot make the key itself large.</p>
     */
    private static String key(String username) {
        if (username == null) {
            return "";
        }

        String normalised = username.trim().toLowerCase(Locale.ROOT);

        return normalised.length() <= MAX_KEY_LENGTH ? normalised : normalised.substring(0, MAX_KEY_LENGTH);
    }
}
