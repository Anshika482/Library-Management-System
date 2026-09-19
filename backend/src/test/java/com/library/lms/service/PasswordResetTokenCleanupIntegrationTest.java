package com.library.lms.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.library.lms.entity.Library;
import com.library.lms.entity.PasswordResetToken;
import com.library.lms.entity.Role;
import com.library.lms.entity.User;
import com.library.lms.repository.LibraryRepository;
import com.library.lms.repository.PasswordResetTokenRepository;
import com.library.lms.repository.UserRepository;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * The password reset token sweep against a real database: what it removes,
 * what it must never remove, and what it says about it.
 *
 * <p><b>Rows are written through JPA</b>, never with SQL, so their times are
 * stored in UTC exactly as the application stores them. <b>The cutoff is
 * exact</b>: the sweep under test runs on a fixed clock, so "a microsecond
 * before the cutoff" and "exactly at it" are values this test can write
 * down.</p>
 *
 * <p><b>Isolation:</b> the throwaway schema the other integration tests use,
 * never the development database, with a fresh account for every test.</p>
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:mysql://localhost:3306/library_db_step129_it"
                + "?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true"
                + "&serverTimezone=UTC",
        "spring.jpa.hibernate.ddl-auto=update",
        "spring.jpa.open-in-view=false"
})
class PasswordResetTokenCleanupIntegrationTest {

    private static final Duration RETENTION = Duration.ofDays(1);

    @Autowired
    private PasswordResetRequestLimiter limiter;

    @Autowired
    private PasswordResetIssuingQueue queue;

    @Autowired
    private PasswordResetTokenIssuer issuer;

    @Autowired
    private PasswordResetTokenRepository tokenRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private LibraryRepository libraryRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private RefreshTokenService refreshTokenService;

    @Autowired
    private LoginAttemptService loginAttemptService;

    /** The application's own service - transactional, on the system clock - for redeeming. */
    @Autowired
    private SelfServicePasswordResetService liveService;

    private User member;

    /** The moment the sweep under test believes it is running at. */
    private LocalDateTime sweepTime;

    /** Tokens that stopped working before this are removed; one that stopped exactly here is kept. */
    private LocalDateTime cutoff;

    private SelfServicePasswordResetService sweepingAt;

    @BeforeEach
    void createAccountAndFixTheClock() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);

        Library library = new Library();
        library.setName("Reset Cleanup Library " + suffix);
        library = libraryRepository.save(library);

        member = new User();
        member.setUsername("reset-cleanup-" + suffix);
        member.setEmail("reset-cleanup-" + suffix + "@example.invalid");
        member.setPassword(passwordEncoder.encode("cleanup-test-old-password"));
        member.setRole(Role.ROLE_MEMBER);
        member.setLibrary(library);
        member = userRepository.save(member);

        Clock fixed = Clock.fixed(Instant.now().truncatedTo(ChronoUnit.SECONDS), ZoneId.systemDefault());
        sweepTime = LocalDateTime.now(fixed);
        cutoff = sweepTime.minus(RETENTION);
        sweepingAt = new SelfServicePasswordResetService(limiter, queue, issuer, tokenRepository, userRepository,
                passwordEncoder, refreshTokenService, loginAttemptService, RETENTION, fixed);
    }

    /** A stored token for the member, written the way the application writes one. */
    private Long store(String rawToken, LocalDateTime expiresAt, LocalDateTime usedAt) {
        PasswordResetToken token = new PasswordResetToken();
        token.setUser(member);
        token.setTokenHash(PasswordResetTokenIssuer.hash(rawToken));
        token.setCreatedAt(expiresAt.minusMinutes(30));
        token.setExpiresAt(expiresAt);
        token.setUsedAt(usedAt);

        return tokenRepository.save(token).getId();
    }

    private Long store(LocalDateTime expiresAt, LocalDateTime usedAt) {
        return store(UUID.randomUUID().toString(), expiresAt, usedAt);
    }

    private boolean stillStored(Long id) {
        return tokenRepository.findById(id).isPresent();
    }

    @Test
    void onlyTokensThatStoppedWorkingBeforeTheCutoffAreRemoved() {
        Long stillValid = store(sweepTime.plusMinutes(20), null);
        Long expiredWellBefore = store(cutoff.minusDays(1), null);
        Long expiredAMicrosecondBefore = store(cutoff.minusNanos(1_000), null);
        Long expiredExactlyAtTheCutoff = store(cutoff, null);
        Long expiredRecently = store(sweepTime.minusMinutes(5), null);
        Long usedBeforeTheCutoff = store(cutoff.plusMinutes(10), cutoff.minusSeconds(1));
        Long usedExactlyAtTheCutoff = store(cutoff.plusMinutes(10), cutoff);
        Long usedRecently = store(sweepTime.plusMinutes(10), sweepTime.minusHours(1));

        int removed = sweepingAt.purgeSpentTokens();

        assertThat(removed).as("this test's three rows at least; other tests may leave their own")
                .isGreaterThanOrEqualTo(3);
        assertThat(stillStored(stillValid)).as("unused and unexpired - never removed").isTrue();
        assertThat(stillStored(expiredWellBefore)).as("expired a day before the cutoff").isFalse();
        assertThat(stillStored(expiredAMicrosecondBefore)).as("expired a microsecond before it").isFalse();
        assertThat(stillStored(expiredExactlyAtTheCutoff)).as("expired exactly at it - kept").isTrue();
        assertThat(stillStored(expiredRecently)).as("expired inside the retention - kept").isTrue();
        assertThat(stillStored(usedBeforeTheCutoff)).as("used before the cutoff, whatever its expiry").isFalse();
        assertThat(stillStored(usedExactlyAtTheCutoff)).as("used exactly at it - kept").isTrue();
        assertThat(stillStored(usedRecently)).as("used inside the retention - kept").isTrue();
    }

    @Test
    void aTokenThatCanStillBeRedeemedStillWorksAfterASweep() {
        String raw = "still-valid-" + UUID.randomUUID();
        store(raw, sweepTime.plusMinutes(20), null);
        store(cutoff.minusDays(2), null);

        sweepingAt.purgeSpentTokens();
        liveService.resetPassword(raw, "cleanup-test-new-password");

        assertThat(passwordEncoder.matches("cleanup-test-new-password",
                userRepository.findById(member.getId()).orElseThrow().getPassword()))
                .as("the sweep left the usable token alone, and it reset the password")
                .isTrue();
    }

    @Test
    void theSweepLogsACountAndNeverATokenOrAHash() {
        String raw = "purged-" + UUID.randomUUID();
        store(raw, cutoff.minusDays(1), null);

        Logger logger = (Logger) LoggerFactory.getLogger(SelfServicePasswordResetService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        int removed;
        try {
            removed = sweepingAt.purgeSpentTokens();
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }

        List<String> lines = appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();

        assertThat(removed).isPositive();
        assertThat(lines).anyMatch(line -> line.contains("Purged") && line.contains(String.valueOf(removed)));
        assertThat(lines).allSatisfy(line -> assertThat(line)
                .doesNotContain(raw)
                .doesNotContain(PasswordResetTokenIssuer.hash(raw))
                .doesNotContain(member.getEmail()));
    }
}
