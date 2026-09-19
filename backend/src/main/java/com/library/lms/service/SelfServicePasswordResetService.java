package com.library.lms.service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.library.lms.entity.AuditAction;
import com.library.lms.entity.PasswordResetToken;
import com.library.lms.entity.User;
import com.library.lms.exception.InvalidPasswordResetTokenException;
import com.library.lms.repository.PasswordResetTokenRepository;
import com.library.lms.repository.UserRepository;

/**
 * Self-service password reset: a request for a token, the redemption of one,
 * and the sweep that clears spent ones away.
 *
 * <p><b>Nothing reveals whether an address has an account - not the answer,
 * and not how long it takes.</b> A request is counted against the address's
 * allowance and handed to {@link PasswordResetIssuingQueue}; that is all the
 * caller waits for, whatever the address. Looking the account up and issuing a
 * token happen afterwards, in {@link PasswordResetTokenIssuer}.</p>
 *
 * <p><b>Single use.</b> A token is spent by the reset it performs, and the row
 * is locked while it is checked, so two simultaneous redemptions cannot both
 * succeed. A reset ends every session of the account, as a password change
 * does.</p>
 *
 * <p><b>Spent tokens are swept away</b> once they have been unusable for
 * {@code security.password-reset.retention}, as refresh tokens are. A token
 * that can still be redeemed is never touched.</p>
 *
 * <p>Nothing here logs a token, a hash, an address or a password - only
 * account ids, counts and what happened.</p>
 */
@Service
public class SelfServicePasswordResetService {

    static final String RETENTION_PROPERTY = "security.password-reset.retention";

    static final String CLEANUP_INTERVAL_PROPERTY = "security.password-reset.cleanup-interval";

    /** Rows removed per statement, so no single transaction locks a whole backlog. */
    static final int PURGE_BATCH_SIZE = 1_000;

    /** Batches per run. A backlog larger than this is cleared over several runs. */
    static final int PURGE_MAX_BATCHES = 50;

    /** Long enough that startup and the first readiness checks are over before the first sweep. */
    static final String PURGE_INITIAL_DELAY = "PT5M";

    private static final Logger log = LoggerFactory.getLogger(SelfServicePasswordResetService.class);

    private final PasswordResetRequestLimiter requestLimiter;

    private final PasswordResetIssuingQueue issuingQueue;

    private final PasswordResetTokenIssuer tokenIssuer;

    private final PasswordResetTokenRepository tokenRepository;

    private final UserRepository userRepository;

    private final PasswordEncoder passwordEncoder;

    private final RefreshTokenService refreshTokenService;

    private final LoginAttemptService loginAttemptService;

    private final AuditService auditService;

    private final Duration retention;

    private final Clock clock;

    /**
     * The service the application runs with, on the system clock.
     *
     * @throws IllegalStateException if the retention is missing, zero or
     *                               negative
     */
    @Autowired
    public SelfServicePasswordResetService(PasswordResetRequestLimiter requestLimiter,
            PasswordResetIssuingQueue issuingQueue, PasswordResetTokenIssuer tokenIssuer,
            PasswordResetTokenRepository tokenRepository, UserRepository userRepository,
            PasswordEncoder passwordEncoder, RefreshTokenService refreshTokenService,
            LoginAttemptService loginAttemptService, AuditService auditService,
            @Value("${" + RETENTION_PROPERTY + "}") Duration retention) {
        this(requestLimiter, issuingQueue, tokenIssuer, tokenRepository, userRepository, passwordEncoder,
                refreshTokenService, loginAttemptService, auditService, retention, Clock.systemDefaultZone());
    }

    SelfServicePasswordResetService(PasswordResetRequestLimiter requestLimiter, PasswordResetIssuingQueue issuingQueue,
            PasswordResetTokenIssuer tokenIssuer, PasswordResetTokenRepository tokenRepository,
            UserRepository userRepository, PasswordEncoder passwordEncoder, RefreshTokenService refreshTokenService,
            LoginAttemptService loginAttemptService, AuditService auditService, Duration retention, Clock clock) {
        if (retention == null || retention.isZero() || retention.isNegative()) {
            throw new IllegalStateException(RETENTION_PROPERTY + " must be a positive duration, such as P1D."
                    + " Set PASSWORD_RESET_TOKEN_RETENTION, or leave it unset for the default.");
        }

        this.requestLimiter = requestLimiter;
        this.issuingQueue = issuingQueue;
        this.tokenIssuer = tokenIssuer;
        this.tokenRepository = tokenRepository;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.refreshTokenService = refreshTokenService;
        this.loginAttemptService = loginAttemptService;
        this.auditService = auditService;
        this.retention = retention;
        this.clock = clock;
    }

    /**
     * Takes a request for a reset link, and nothing more.
     *
     * <p>The same two steps for every address: count the request against the
     * address's allowance, and hand it to the issuing queue. No account is
     * looked up and nothing is written here, so the caller's wait is the same
     * whether the address has an account or not. A request over the limit, or
     * one that finds the queue full, is dropped; the caller cannot tell.</p>
     *
     * @param email the address submitted, already validated as an address
     */
    public void requestReset(String email) {
        if (!requestLimiter.tryAcquire(email)) {
            log.warn("Password reset request dropped by the rate limit");
            return;
        }

        if (!issuingQueue.submit(() -> issue(email))) {
            log.warn("Password reset request dropped: the issuing queue is full");
        }
    }

    /** Runs on the queue's thread. A failure is logged by type only - its message could carry the address. */
    private void issue(String email) {
        try {
            tokenIssuer.issueFor(email);
        } catch (RuntimeException failure) {
            log.error("Password reset could not be issued: {}", failure.getClass().getSimpleName());
        }
    }

    /**
     * Redeems a reset token for a new password.
     *
     * <p>The token must exist, be unused, be unexpired, and belong to an
     * account that is still enabled and unlocked; every failure is the same
     * {@link InvalidPasswordResetTokenException}. The row is locked while it is
     * checked, so two simultaneous redemptions cannot both succeed.</p>
     *
     * <p>On success the token and every other unused token of the account are
     * spent, the password is stored through the application's BCrypt encoder,
     * every refresh session of the account is revoked, and its failed-login
     * block is cleared.</p>
     *
     * @param token       the token presented
     * @param newPassword the replacement, already validated as 8 to 72
     *                    characters
     * @throws InvalidPasswordResetTokenException if the token cannot be used
     */
    @Transactional
    public void resetPassword(String token, String newPassword) {
        PasswordResetToken presented = tokenRepository.findByTokenHash(PasswordResetTokenIssuer.hash(token))
                .orElseThrow(InvalidPasswordResetTokenException::new);

        LocalDateTime now = now();
        User user = presented.getUser();

        if (presented.getUsedAt() != null) {
            log.warn("A used or superseded password reset token was presented for user id={}", user.getId());
            recordRefusedRedemption(user);
            throw new InvalidPasswordResetTokenException();
        }

        if (!now.isBefore(presented.getExpiresAt())) {
            log.info("An expired password reset token was presented for user id={}", user.getId());
            recordRefusedRedemption(user);
            throw new InvalidPasswordResetTokenException();
        }

        if (!user.isEnabled() || !user.isAccountNonLocked()) {
            log.warn("Password reset refused for user id={}: the account is disabled or locked", user.getId());
            recordRefusedRedemption(user);
            throw new InvalidPasswordResetTokenException();
        }

        presented.setUsedAt(now);
        tokenIssuer.spendOutstanding(user, now);

        user.setPassword(passwordEncoder.encode(newPassword));
        userRepository.save(user);

        int revoked = refreshTokenService.revokeAllFor(user);
        loginAttemptService.reset(user.getUsername());
        auditService.recordSuccess(AuditAction.PASSWORD_RESET_COMPLETED, user.getLibrary().getId(), null,
                AuditTarget.user(user.getId()));

        log.info("Password reset completed for user id={}: {} live refresh token(s) revoked, login block cleared",
                user.getId(), revoked);
    }

    /**
     * Removes reset tokens that stopped working longer ago than the retention
     * period.
     *
     * <p><b>A token that can still be redeemed is never touched.</b> One is
     * removed only if it expired before the cutoff, or was used or superseded
     * before it. An unused token that has not expired has neither, and since
     * the cutoff lies in the past, its expiry is after it.</p>
     *
     * <p>The rows are kept that long at all so that a spent token presented
     * again is logged as reuse rather than as an unknown token - the answer is
     * the same 400 either way.</p>
     *
     * <p><b>Not one transaction.</b> Each batch is its own, so a first sweep
     * over a long backlog cannot hold a huge number of row locks; a backlog
     * larger than {@link #PURGE_MAX_BATCHES} batches is left for the next run.
     * Every instance runs this, and deleting the same rows twice is harmless.
     * Only counts are logged.</p>
     *
     * @return how many rows were removed
     */
    @Scheduled(initialDelayString = PURGE_INITIAL_DELAY, fixedDelayString = "${" + CLEANUP_INTERVAL_PROPERTY + "}")
    public int purgeSpentTokens() {
        LocalDateTime cutoff = now().minus(retention);
        int removed = 0;

        for (int batch = 0; batch < PURGE_MAX_BATCHES; batch++) {
            int deleted = tokenRepository.deleteSpentBefore(cutoff, PURGE_BATCH_SIZE);
            removed += deleted;

            if (deleted < PURGE_BATCH_SIZE) {
                if (removed > 0) {
                    log.info("Purged {} password reset token row(s) that stopped working before the retention"
                            + " cutoff", removed);
                }

                return removed;
            }
        }

        log.warn("Purged {} password reset token row(s) and stopped at the per-run cap; the next run continues",
                removed);

        return removed;
    }

    /**
     * A token that named a real account but could not be used. No actor: nobody
     * is signed in, and presenting a token proves nothing about who presents it.
     * A token that matches no stored row names no account and is not recorded.
     */
    private void recordRefusedRedemption(User user) {
        auditService.recordFailure(AuditAction.PASSWORD_RESET_COMPLETED, user.getLibrary().getId(), null,
                AuditTarget.user(user.getId()));
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }
}
