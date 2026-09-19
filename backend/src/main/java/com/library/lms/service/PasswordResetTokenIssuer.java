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
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.library.lms.entity.PasswordResetToken;
import com.library.lms.entity.User;
import com.library.lms.repository.PasswordResetTokenRepository;
import com.library.lms.repository.UserRepository;

/**
 * Issues self-service password reset tokens - the part of a reset request that
 * depends on whether the address has an account.
 *
 * <p>It runs on {@link PasswordResetIssuingQueue}'s thread, after the request
 * has been answered, so none of the difference between an address with an
 * account and one without reaches the caller - not in the answer and not in
 * how long it took.</p>
 *
 * <p><b>Tokens are opaque, random and stored only as a hash.</b> 256 bits from
 * {@link SecureRandom}, handed to delivery once as a
 * {@link PasswordResetRequested} event, and kept only as a SHA-256. A fast hash
 * is right here: the input already carries 256 bits of entropy, and the hash
 * must be deterministic so a presented token can be found by it.</p>
 *
 * <p><b>Short-lived, one at a time.</b> A token lasts
 * {@code security.password-reset.token-validity} - minutes, capped at a day -
 * and issuing one spends every earlier unused token of the account.</p>
 *
 * <p>Nothing here logs a token, a hash or an address - only account ids.</p>
 */
@Component
public class PasswordResetTokenIssuer {

    static final String VALIDITY_PROPERTY = "security.password-reset.token-validity";

    /** A reset link is meant to be used now. A day is already generous; longer is a misconfiguration. */
    static final Duration MAX_VALIDITY = Duration.ofHours(24);

    /** 256 bits. */
    private static final int TOKEN_BYTES = 32;

    private static final Logger log = LoggerFactory.getLogger(PasswordResetTokenIssuer.class);

    private final PasswordResetTokenRepository tokenRepository;

    private final UserRepository userRepository;

    private final ApplicationEventPublisher events;

    private final Duration validity;

    private final Clock clock;

    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * The issuer the application runs with, on the system clock.
     *
     * @throws IllegalStateException if the token lifetime is missing, not
     *                               positive, or longer than a day
     */
    @Autowired
    public PasswordResetTokenIssuer(PasswordResetTokenRepository tokenRepository, UserRepository userRepository,
            ApplicationEventPublisher events, @Value("${" + VALIDITY_PROPERTY + "}") Duration validity) {
        this(tokenRepository, userRepository, events, validity, Clock.systemDefaultZone());
    }

    PasswordResetTokenIssuer(PasswordResetTokenRepository tokenRepository, UserRepository userRepository,
            ApplicationEventPublisher events, Duration validity, Clock clock) {
        if (validity == null || validity.isZero() || validity.isNegative() || validity.compareTo(MAX_VALIDITY) > 0) {
            throw new IllegalStateException(VALIDITY_PROPERTY + " must be a positive duration of at most PT24H,"
                    + " such as PT30M. Set PASSWORD_RESET_TOKEN_VALIDITY, or leave it unset for the default.");
        }

        this.tokenRepository = tokenRepository;
        this.userRepository = userRepository;
        this.events = events;
        this.validity = validity;
        this.clock = clock;
    }

    /**
     * Issues a reset token for the account with this address, if there is one
     * that may have it.
     *
     * <ol>
     *   <li>No account with the address: nothing is written.</li>
     *   <li>A disabled or locked account: nothing is written. A reset would not
     *       let it sign in.</li>
     *   <li>Otherwise every earlier unused token of the account is spent, a new
     *       one is stored as a hash, and a {@link PasswordResetRequested} event
     *       carries the token to delivery - after this transaction commits, for
     *       listeners that ask for that.</li>
     * </ol>
     *
     * @param email the address submitted, already validated as an address
     */
    @Transactional
    public void issueFor(String email) {
        Optional<User> found = userRepository.findByEmail(email.trim());

        if (found.isEmpty()) {
            log.debug("Password reset requested for an address with no account; nothing issued");
            return;
        }

        User user = found.get();

        if (!user.isEnabled() || !user.isAccountNonLocked()) {
            log.info("Password reset not issued for user id={}: the account is disabled or locked", user.getId());
            return;
        }

        LocalDateTime now = LocalDateTime.now(clock);
        int superseded = spendOutstanding(user, now);

        String token = newToken();

        PasswordResetToken stored = new PasswordResetToken();
        stored.setUser(user);
        stored.setTokenHash(hash(token));
        stored.setCreatedAt(now);
        stored.setExpiresAt(now.plus(validity));
        tokenRepository.save(stored);

        events.publishEvent(new PasswordResetRequested(user.getId(), user.getEmail(), token, stored.getExpiresAt()));

        log.info("Password reset token issued for user id={} ({} earlier token(s) superseded)",
                user.getId(), superseded);
    }

    /**
     * Marks every unused token of the account as spent. Joins the caller's
     * transaction: issuing a new token, or redeeming one.
     *
     * @return how many tokens were spent
     */
    public int spendOutstanding(User user, LocalDateTime now) {
        List<PasswordResetToken> outstanding = tokenRepository.findByUserIdAndUsedAtIsNull(user.getId());

        outstanding.forEach(token -> token.setUsedAt(now));

        return outstanding.size();
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
