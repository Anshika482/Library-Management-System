package com.library.lms.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

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
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.library.lms.entity.Library;
import com.library.lms.entity.RefreshToken;
import com.library.lms.entity.Role;
import com.library.lms.entity.User;
import com.library.lms.repository.LibraryRepository;
import com.library.lms.repository.RefreshTokenRepository;
import com.library.lms.repository.UserRepository;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * The retention sweep against a real database: which rows it removes, which it
 * must not, and what it says about it.
 *
 * <p><b>Rows are written through JPA</b>, never with SQL. A {@code DATETIME}
 * column holds UTC because the driver converts what Hibernate binds; a raw
 * {@code INSERT} would store local time instead and the row would sit five and a
 * half hours away from where this test thinks it put it.</p>
 *
 * <p><b>The cutoff is exact.</b> The sweep under test runs on a fixed clock, so
 * "one microsecond before the cutoff" and "exactly at the cutoff" are values
 * this test can write down - the boundary is asserted, not approximated.</p>
 *
 * <p><b>Isolation:</b> the throwaway schema the other integration tests use,
 * never the development database, with fresh accounts for every test.</p>
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:mysql://localhost:3306/library_db_step129_it"
                + "?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true"
                + "&serverTimezone=UTC",
        "spring.jpa.hibernate.ddl-auto=update",
        "spring.jpa.open-in-view=false"
})
@AutoConfigureMockMvc
class RefreshTokenRetentionIntegrationTest {

    /** Test-only credential, never a real one. */
    private static final String TEST_PASSWORD = "retention-test-only-password";

    private static final Duration VALIDITY = Duration.ofDays(7);

    private static final Duration RETENTION = Duration.ofDays(30);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private LibraryRepository libraryRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private RefreshTokenService liveService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private String suffix;
    private User member;

    /** The moment the sweep under test believes it is running at. */
    private LocalDateTime sweepTime;

    /** Sessions that ended before this are removed; one that ended exactly here is kept. */
    private LocalDateTime cutoff;

    private RefreshTokenService sweepingAt;

    @BeforeEach
    void createAccountAndFixTheClock() {
        suffix = UUID.randomUUID().toString().substring(0, 8);

        Library library = new Library();
        library.setName("Retention Library " + suffix);
        library = libraryRepository.save(library);

        member = new User();
        member.setUsername("retention-member-" + suffix);
        member.setEmail("retention-member-" + suffix + "@example.invalid");
        member.setPassword(passwordEncoder.encode(TEST_PASSWORD));
        member.setRole(Role.ROLE_MEMBER);
        member.setLibrary(library);
        member = userRepository.save(member);

        Clock fixed = Clock.fixed(Instant.now().truncatedTo(ChronoUnit.SECONDS), ZoneId.systemDefault());
        sweepTime = LocalDateTime.now(fixed);
        cutoff = sweepTime.minus(RETENTION);
        sweepingAt = new RefreshTokenService(refreshTokenRepository, userRepository, VALIDITY, RETENTION, fixed);
    }

    /** A stored token of a session that ended at {@code expiresAt}, written the way the application writes it. */
    private Long storeSessionEndingAt(LocalDateTime expiresAt, LocalDateTime revokedAt) {
        RefreshToken token = new RefreshToken();
        token.setUser(member);
        token.setTokenHash(RefreshTokenService.hash(UUID.randomUUID().toString()));
        token.setFamilyId(UUID.randomUUID().toString());
        token.setCreatedAt(expiresAt.minus(VALIDITY));
        token.setExpiresAt(expiresAt);
        token.setRevokedAt(revokedAt);

        return refreshTokenRepository.save(token).getId();
    }

    private boolean stillStored(Long id) {
        return refreshTokenRepository.findById(id).isPresent();
    }

    private String login() throws Exception {
        String body = objectMapper.createObjectNode()
                .put("username", member.getUsername())
                .put("password", TEST_PASSWORD)
                .toString();

        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);

        return objectMapper.readTree(result.getResponse().getContentAsString()).path("refreshToken").asText();
    }

    private MvcResult refresh(String refreshToken) throws Exception {
        String body = objectMapper.createObjectNode().put("refreshToken", refreshToken).toString();

        return mockMvc.perform(post("/api/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)).andReturn();
    }

    // ---------- what the sweep removes ----------

    @Test
    void onlySessionsThatEndedBeforeTheCutoffAreRemoved() {
        Long wellPast = storeSessionEndingAt(cutoff.minusDays(1), cutoff.minusDays(1));
        Long aMicrosecondPast = storeSessionEndingAt(cutoff.minusNanos(1_000), null);
        Long exactlyAtTheCutoff = storeSessionEndingAt(cutoff, null);
        Long justInside = storeSessionEndingAt(cutoff.plusSeconds(1), null);
        Long live = storeSessionEndingAt(sweepTime.plus(VALIDITY), null);

        int removed = sweepingAt.purgeExpiredSessions();

        assertThat(removed).as("this test's two rows at least; other tests may leave their own")
                .isGreaterThanOrEqualTo(2);
        assertThat(stillStored(wellPast)).as("ended a day before the cutoff").isFalse();
        assertThat(stillStored(aMicrosecondPast)).as("ended a microsecond before the cutoff").isFalse();
        assertThat(stillStored(exactlyAtTheCutoff)).as("ended exactly at the cutoff - kept").isTrue();
        assertThat(stillStored(justInside)).as("ended a second after the cutoff - kept").isTrue();
        assertThat(stillStored(live)).as("still running - kept").isTrue();
    }

    @Test
    void aRevokedTokenOfASessionInsideTheWindowIsKept() {
        Long revokedYesterday = storeSessionEndingAt(sweepTime.plusDays(6), sweepTime.minusDays(1));
        Long revokedLongAgoOfADeadSession = storeSessionEndingAt(cutoff.minusDays(2), cutoff.minusDays(9));

        sweepingAt.purgeExpiredSessions();

        assertThat(stillStored(revokedYesterday))
                .as("revoked, but its session is still running - the row is what makes reuse recognisable")
                .isTrue();
        assertThat(stillStored(revokedLongAgoOfADeadSession)).isFalse();
    }

    @Test
    void anEmptySweepRemovesNothing() {
        Long live = storeSessionEndingAt(sweepTime.plus(VALIDITY), null);

        assertThat(liveService.purgeExpiredSessions())
                .as("the application's own sweep, on the system clock, over sessions that all end in the future")
                .isZero();
        assertThat(stillStored(live)).isTrue();
    }

    // ---------- what must keep working afterwards ----------

    @Test
    void aLiveSessionStillRefreshesAfterASweep() throws Exception {
        String refreshToken = login();

        sweepingAt.purgeExpiredSessions();

        assertThat(refresh(refreshToken).getResponse().getStatus())
                .as("the sweep must not touch a session that is still running")
                .isEqualTo(200);
    }

    @Test
    void reuseOfARevokedTokenIsStillDetectedAfterASweep() throws Exception {
        String first = login();
        MvcResult rotated = refresh(first);
        assertThat(rotated.getResponse().getStatus()).isEqualTo(200);
        String second = objectMapper.readTree(rotated.getResponse().getContentAsString())
                .path("refreshToken").asText();

        sweepingAt.purgeExpiredSessions();

        // The first token was revoked when it was exchanged. Presenting it
        // again is reuse, which ends the whole session - so the replacement
        // stops working too.
        assertThat(refresh(first).getResponse().getStatus()).as("reuse refused").isEqualTo(401);
        assertThat(refresh(second).getResponse().getStatus()).as("the session it belonged to has ended").isEqualTo(401);
    }

    // ---------- what it says about it ----------

    @Test
    void theSweepLogsACountAndNeitherATokenNorAHash() {
        String token = UUID.randomUUID().toString();
        String hash = RefreshTokenService.hash(token);

        RefreshToken stored = new RefreshToken();
        stored.setUser(member);
        stored.setTokenHash(hash);
        stored.setFamilyId(UUID.randomUUID().toString());
        stored.setCreatedAt(cutoff.minusDays(8));
        stored.setExpiresAt(cutoff.minusDays(1));
        stored.setRevokedAt(cutoff.minusDays(1));
        refreshTokenRepository.save(stored);

        Logger logger = (Logger) LoggerFactory.getLogger(RefreshTokenService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        int removed;
        try {
            removed = sweepingAt.purgeExpiredSessions();
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }

        List<String> lines = appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();

        assertThat(removed).isPositive();
        assertThat(lines).as("one line, saying how many").anyMatch(line -> line.contains("Purged")
                && line.contains(String.valueOf(removed)));
        assertThat(lines).as("never a token or a hash").allSatisfy(line -> assertThat(line)
                .doesNotContain(token)
                .doesNotContain(hash));
    }
}
