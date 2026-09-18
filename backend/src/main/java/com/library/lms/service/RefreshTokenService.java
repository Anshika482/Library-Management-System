package com.library.lms.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.library.lms.entity.RefreshToken;
import com.library.lms.entity.User;
import com.library.lms.exception.InvalidRefreshTokenException;
import com.library.lms.exception.UserNotFoundException;
import com.library.lms.repository.RefreshTokenRepository;
import com.library.lms.repository.UserRepository;

/**
 * Refresh tokens: issued at login, exchanged one-for-one on refresh, revoked at
 * logout.
 *
 * <p><b>Opaque, random and stored only as a hash.</b> A refresh token is 256
 * bits from {@link SecureRandom}, not a JWT: it carries nothing and means
 * nothing except as a key to a stored row. The server keeps its SHA-256 and
 * never the token. A fast hash is the right one here, unlike for passwords: the
 * input already has 256 bits of entropy, so there is nothing for a slow hash to
 * protect, and the hash must be deterministic so a presented token can be found
 * by it.</p>
 *
 * <p><b>Every token works once.</b> A refresh uses up the token presented and
 * issues a replacement in the same session. Presenting a used token again means
 * it was copied - by the client and someone else, and there is no telling which
 * is which - so the whole session is revoked and both must log in again.</p>
 *
 * <p><b>A session has a fixed end.</b> Its expiry is set at login and copied to
 * every replacement, so refreshing keeps a session alive only until then; a
 * stolen token cannot be refreshed forever.</p>
 *
 * <p><b>Account status is checked on every refresh.</b> A disabled or locked
 * account cannot refresh, and its session is revoked, so restoring the account
 * does not bring an old session back.</p>
 *
 * <p><b>Rows outlive their session.</b> A revoked or expired row is what makes
 * reuse of an old token recognisable, so rows are kept for
 * {@code security.refresh-token.retention} after the session ended and only
 * then swept away by {@link #purgeExpiredSessions()}.</p>
 *
 * <p><b>Nothing here logs a token or a hash</b> - only account ids, counts, and
 * what happened.</p>
 */
@Service
public class RefreshTokenService {

    static final String VALIDITY_PROPERTY = "security.refresh-token.validity";

    static final String RETENTION_PROPERTY = "security.refresh-token.retention";

    static final String CLEANUP_INTERVAL_PROPERTY = "security.refresh-token.cleanup-interval";

    /** Rows removed per statement, so no single transaction locks a whole backlog. */
    static final int PURGE_BATCH_SIZE = 1_000;

    /** Batches per run. A backlog larger than this is cleared over several runs. */
    static final int PURGE_MAX_BATCHES = 50;

    /** Long enough that startup and the first readiness checks are over before the first sweep. */
    static final String PURGE_INITIAL_DELAY = "PT5M";

    /** 256 bits. */
    private static final int TOKEN_BYTES = 32;

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenService.class);

    /**
     * The outcome of a successful refresh: whose session it is, and the token
     * that replaces the one presented.
     *
     * <p>{@code toString()} leaves the token out, so the record cannot carry it
     * into a log line.</p>
     */
    public record Rotation(String username, String refreshToken) {

        @Override
        public String toString() {
            return "Rotation[username=" + username + ", refreshToken=<redacted>]";
        }
    }

    private final RefreshTokenRepository refreshTokenRepository;

    private final UserRepository userRepository;

    private final Duration validity;

    private final Duration retention;

    private final Clock clock;

    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * The service the application runs with, on the system clock.
     *
     * @param validity  how long a session lasts from login
     * @param retention how long a session's rows are kept after it has ended
     * @throws IllegalStateException if either duration is missing, zero or
     *                               negative, or if retention is shorter than
     *                               validity
     */
    @Autowired
    public RefreshTokenService(RefreshTokenRepository refreshTokenRepository, UserRepository userRepository,
            @Value("${" + VALIDITY_PROPERTY + "}") Duration validity,
            @Value("${" + RETENTION_PROPERTY + "}") Duration retention) {
        this(refreshTokenRepository, userRepository, validity, retention, Clock.systemDefaultZone());
    }

    RefreshTokenService(RefreshTokenRepository refreshTokenRepository, UserRepository userRepository,
            Duration validity, Duration retention, Clock clock) {
        if (validity == null || validity.isZero() || validity.isNegative()) {
            throw new IllegalStateException(VALIDITY_PROPERTY + " must be a positive duration, such as P7D."
                    + " Set JWT_REFRESH_TOKEN_VALIDITY, or leave it unset for the default.");
        }

        if (retention == null || retention.isZero() || retention.isNegative()) {
            throw new IllegalStateException(RETENTION_PROPERTY + " must be a positive duration, such as P30D."
                    + " Set JWT_REFRESH_TOKEN_RETENTION, or leave it unset for the default.");
        }

        // Reuse of an old token is recognised only while its row is still
        // there. Keeping rows for less time than a session lasts would delete
        // that evidence out from under a session that is still running.
        if (retention.compareTo(validity) < 0) {
            throw new IllegalStateException(RETENTION_PROPERTY + " (" + retention + ") must be at least "
                    + VALIDITY_PROPERTY + " (" + validity + "), so a reused token is still recognised for as long"
                    + " as a session can last. Raise JWT_REFRESH_TOKEN_RETENTION or lower"
                    + " JWT_REFRESH_TOKEN_VALIDITY.");
        }

        this.refreshTokenRepository = refreshTokenRepository;
        this.userRepository = userRepository;
        this.validity = validity;
        this.retention = retention;
        this.clock = clock;
    }

    /**
     * Starts a session for an account that has just logged in.
     *
     * @param username the account, already authenticated
     * @return the refresh token - the only copy there will ever be
     */
    @Transactional
    public String issue(String username) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new UserNotFoundException(username));

        LocalDateTime now = now();
        String token = newToken();

        store(user, token, UUID.randomUUID().toString(), now, now.plus(validity));

        return token;
    }

    /**
     * Exchanges a live refresh token for its replacement.
     *
     * <p><b>{@code noRollbackFor} matters.</b> Several refusals revoke the
     * session before refusing - a reused token, a disabled account - and the
     * refusal is an exception. Rolled back, the revocation would be undone and
     * the refusal would protect nothing.</p>
     *
     * @param refreshToken the token presented
     * @return whose session it is, and the new token
     * @throws InvalidRefreshTokenException if the token is unknown, already used
     *                                      or revoked, expired, or belongs to an
     *                                      account that is disabled or locked
     */
    @Transactional(noRollbackFor = InvalidRefreshTokenException.class)
    public Rotation rotate(String refreshToken) {
        RefreshToken presented = refreshTokenRepository.findByTokenHash(hash(refreshToken))
                .orElseThrow(InvalidRefreshTokenException::new);

        LocalDateTime now = now();
        User user = presented.getUser();

        if (presented.getRevokedAt() != null) {
            int revoked = revokeSession(presented.getFamilyId(), now);
            log.warn("A revoked refresh token was presented for user id={}: session ended, {} live token(s) revoked",
                    user.getId(), revoked);
            throw new InvalidRefreshTokenException();
        }

        if (!now.isBefore(presented.getExpiresAt())) {
            log.info("An expired refresh token was presented for user id={}", user.getId());
            throw new InvalidRefreshTokenException();
        }

        if (!user.isEnabled() || !user.isAccountNonLocked()) {
            revokeSession(presented.getFamilyId(), now);
            log.warn("Refresh refused for user id={}: the account is disabled or locked; session ended", user.getId());
            throw new InvalidRefreshTokenException();
        }

        presented.setRevokedAt(now);

        String replacement = newToken();
        store(user, replacement, presented.getFamilyId(), now, presented.getExpiresAt());

        return new Rotation(user.getUsername(), replacement);
    }

    /**
     * Ends the session a refresh token belongs to.
     *
     * <p>Every live token in the session is revoked, whichever token of it was
     * presented. An unknown token changes nothing and is not an error: logout
     * answers the same whatever it is given.</p>
     *
     * @param refreshToken the token presented
     */
    @Transactional
    public void revoke(String refreshToken) {
        refreshTokenRepository.findByTokenHash(hash(refreshToken)).ifPresent(token -> {
            int revoked = revokeSession(token.getFamilyId(), now());
            log.info("Session ended by logout for user id={}: {} live token(s) revoked",
                    token.getUser().getId(), revoked);
        });
    }

    /**
     * Ends every session an account has.
     *
     * <p>Joins the caller's transaction, so the sessions end together with
     * whatever made them end - a password change.</p>
     *
     * @param user the account
     * @return how many live tokens were revoked
     */
    @Transactional
    public int revokeAllFor(User user) {
        List<RefreshToken> live = refreshTokenRepository.findByUserIdAndRevokedAtIsNull(user.getId());
        LocalDateTime now = now();

        live.forEach(token -> token.setRevokedAt(now));

        return live.size();
    }

    private int revokeSession(String familyId, LocalDateTime now) {
        List<RefreshToken> live = refreshTokenRepository.findByFamilyIdAndRevokedAtIsNull(familyId);

        live.forEach(token -> token.setRevokedAt(now));

        return live.size();
    }

    private void store(User user, String token, String familyId, LocalDateTime createdAt, LocalDateTime expiresAt) {
        RefreshToken stored = new RefreshToken();
        stored.setUser(user);
        stored.setTokenHash(hash(token));
        stored.setFamilyId(familyId);
        stored.setCreatedAt(createdAt);
        stored.setExpiresAt(expiresAt);

        refreshTokenRepository.save(stored);
    }

    /**
     * Removes the rows of sessions that ended longer ago than the retention
     * period.
     *
     * <p><b>A live session is never touched.</b> The condition is the session's
     * end - {@code expires_at} - which is in the future for every token that
     * still works, so nothing this sweep deletes could have been presented
     * successfully. Revocation is deliberately not part of the condition: a
     * token revoked at logout belongs to a session that may still be running,
     * and its row is what makes presenting that token again recognisable as
     * reuse rather than as an unknown token.</p>
     *
     * <p><b>Not one transaction.</b> Each batch is its own, so a first sweep
     * over a long backlog cannot hold a huge number of row locks. A backlog
     * larger than {@link #PURGE_MAX_BATCHES} batches is left for the next run
     * rather than chased to the end.</p>
     *
     * <p>Every instance runs this; deleting the same expired rows twice is
     * harmless, so no coordination is needed. Only counts are logged - never a
     * token, a hash or an account.</p>
     *
     * @return how many rows were removed
     */
    @Scheduled(initialDelayString = PURGE_INITIAL_DELAY, fixedDelayString = "${" + CLEANUP_INTERVAL_PROPERTY + "}")
    public int purgeExpiredSessions() {
        LocalDateTime cutoff = now().minus(retention);
        int removed = 0;

        for (int batch = 0; batch < PURGE_MAX_BATCHES; batch++) {
            int deleted = refreshTokenRepository.deleteExpiredBefore(cutoff, PURGE_BATCH_SIZE);
            removed += deleted;

            if (deleted < PURGE_BATCH_SIZE) {
                if (removed > 0) {
                    log.info("Purged {} refresh token row(s) of sessions that ended before the retention cutoff",
                            removed);
                }

                return removed;
            }
        }

        log.warn("Purged {} refresh token row(s) and stopped at the per-run cap; the next run continues", removed);

        return removed;
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    /** A new token: 256 random bits, URL-safe Base64 without padding - 43 characters. */
    String newToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(bytes);

        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** SHA-256 of a token as lowercase hex: what is stored, and what a presented token is looked up by. */
    static String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));

            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable on this JVM", exception);
        }
    }
}
