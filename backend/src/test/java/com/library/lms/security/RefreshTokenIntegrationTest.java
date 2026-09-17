package com.library.lms.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.library.lms.entity.Library;
import com.library.lms.entity.RefreshToken;
import com.library.lms.entity.Role;
import com.library.lms.entity.User;
import com.library.lms.repository.LibraryRepository;
import com.library.lms.repository.RefreshTokenRepository;
import com.library.lms.repository.UserRepository;
import com.library.lms.service.JwtService;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * Proves refresh-token sessions end to end: issued at login, rotated on every
 * refresh, refused on reuse, revoked at logout, bounded in time, and ended for
 * an account that is disabled, locked, or has its password changed.
 *
 * <p><b>What is stored is read straight from the table</b>, with SQL rather
 * than through the application: the hash of the token is there and the token
 * is not, anywhere.</p>
 *
 * <p><b>Refusals are indistinguishable.</b> Every refused refresh - unknown,
 * used, logged out, expired, disabled, locked - is the same 401 with the same
 * sentence, checked by one helper.</p>
 *
 * <p><b>The access token is unchanged.</b> A refreshed access token is an
 * ordinary one, carrying the account's own role and library and nothing more,
 * and an access token already issued is not revoked by logout - the stated
 * limit of a self-contained token.</p>
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
class RefreshTokenIntegrationTest {

    /** Test-only credential, never a real one, and never reused outside this class. */
    private static final String TEST_PASSWORD = "step159-test-only-password";

    private static final String REFUSED = "Invalid or expired refresh token.";

    private static final SecureRandom RANDOM = new SecureRandom();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private LibraryRepository libraryRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private record Session(String accessToken, String refreshToken) {
    }

    private record StoredToken(String tokenHash, String familyId, LocalDateTime createdAt,
                               LocalDateTime expiresAt, LocalDateTime revokedAt) {
    }

    private String suffix;
    private User admin;
    private User member;
    private User otherLibraryMember;
    private String adminToken;

    // ---------- fixtures ----------

    @BeforeEach
    void createAccounts() throws Exception {
        suffix = UUID.randomUUID().toString().substring(0, 8);

        Library library = newLibrary("A");
        admin = persistUser(library, Role.ROLE_ADMIN, "admin");
        member = persistUser(library, Role.ROLE_MEMBER, "member");
        otherLibraryMember = persistUser(newLibrary("B"), Role.ROLE_MEMBER, "other-member");

        adminToken = login(admin).accessToken();
    }

    private Library newLibrary(String label) {
        Library library = new Library();
        library.setName("Step159 Library " + label + " " + suffix);
        return libraryRepository.save(library);
    }

    private User persistUser(Library library, Role role, String label) {
        String username = "step159-" + label + "-" + suffix;

        User user = new User();
        user.setUsername(username);
        user.setEmail(username + "@example.invalid");
        user.setPassword(passwordEncoder.encode(TEST_PASSWORD));
        user.setRole(role);
        user.setLibrary(library);
        return userRepository.save(user);
    }

    // ---------- helpers ----------

    private Session login(User user) throws Exception {
        return login(user, TEST_PASSWORD);
    }

    private Session login(User user, String password) throws Exception {
        String body = objectMapper.createObjectNode()
                .put("username", user.getUsername())
                .put("password", password)
                .toString();

        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();

        assertThat(status(result)).as("login for %s", user.getUsername()).isEqualTo(200);
        return sessionFrom(result);
    }

    private Session sessionFrom(MvcResult result) throws Exception {
        assertThat(status(result)).as("a new pair of tokens").isEqualTo(200);
        JsonNode body = json(result);
        return new Session(body.path("token").asText(), body.path("refreshToken").asText());
    }

    private String tokenBody(String refreshToken) {
        return objectMapper.createObjectNode().put("refreshToken", refreshToken).toString();
    }

    private MvcResult refresh(String refreshToken) throws Exception {
        return mockMvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                .content(tokenBody(refreshToken))).andReturn();
    }

    private MvcResult logout(String refreshToken) throws Exception {
        return mockMvc.perform(post("/api/auth/logout").contentType(MediaType.APPLICATION_JSON)
                .content(tokenBody(refreshToken))).andReturn();
    }

    private int booksWith(String accessToken) throws Exception {
        return status(mockMvc.perform(get("/api/books").header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andReturn());
    }

    private MvcResult setStatus(User target, String body, String token) throws Exception {
        return mockMvc.perform(patch("/api/users/{id}/status", target.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
    }

    /** Every refresh token row of an account, oldest first, read with plain SQL. */
    private List<StoredToken> rowsFor(User user) {
        return jdbcTemplate.query(
                "SELECT token_hash, family_id, created_at, expires_at, revoked_at FROM refresh_tokens"
                        + " WHERE user_id = ? ORDER BY id",
                (row, n) -> new StoredToken(
                        row.getString("token_hash"),
                        row.getString("family_id"),
                        row.getObject("created_at", LocalDateTime.class),
                        row.getObject("expires_at", LocalDateTime.class),
                        row.getObject("revoked_at", LocalDateTime.class)),
                user.getId());
    }

    private StoredToken rowFor(User user, String refreshToken) {
        String hash = sha256(refreshToken);
        return rowsFor(user).stream().filter(row -> row.tokenHash().equals(hash)).findFirst().orElseThrow();
    }

    /**
     * Moves a session's end a minute into the past.
     *
     * <p>Through JPA, not SQL. On a connection set to UTC, Hibernate and a plain
     * JDBC write do not store a {@code LocalDateTime} the same way, so a time
     * written with SQL would not read back as the same moment in the
     * application. Written the way the application writes it, it does.</p>
     */
    private void expire(String refreshToken) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            RefreshToken stored = refreshTokenRepository.findByTokenHash(sha256(refreshToken)).orElseThrow();
            stored.setExpiresAt(LocalDateTime.now().minusMinutes(1));
        });
    }

    private static String sha256(String value) throws IllegalStateException {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String randomToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private static int status(MvcResult result) {
        return result.getResponse().getStatus();
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    /** A refused refresh: always 401, always the same sentence, in the usual error shape. */
    private void assertRefused(String label, MvcResult result) throws Exception {
        assertThat(status(result)).as(label).isEqualTo(401);
        assertThat(fieldNames(json(result))).as(label).containsExactlyInAnyOrder("status", "message", "timestamp");
        assertThat(json(result).path("message").asText()).as(label).isEqualTo(REFUSED);
    }

    private void assertError(MvcResult result, int expectedStatus, String expectedMessage) throws Exception {
        assertThat(status(result)).isEqualTo(expectedStatus);
        assertThat(json(result).path("message").asText()).isEqualTo(expectedMessage);
    }

    // ---------- issued at login, stored as a hash ----------

    @Test
    void loginIssuesARefreshTokenThatIsStoredOnlyAsItsHash() throws Exception {
        Session session = login(member);

        assertThat(session.accessToken().split("\\.")).as("the access token is still a JWT").hasSize(3);
        assertThat(session.refreshToken())
                .as("256 random bits, URL-safe - and not a JWT")
                .matches("[A-Za-z0-9_-]{43}")
                .isNotEqualTo(session.accessToken());

        List<StoredToken> rows = rowsFor(member);
        assertThat(rows).hasSize(1);
        StoredToken row = rows.get(0);
        assertThat(row.tokenHash()).isEqualTo(sha256(session.refreshToken()));
        assertThat(row.revokedAt()).isNull();
        assertThat(Duration.between(row.createdAt(), row.expiresAt())).isEqualTo(Duration.ofDays(7));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM refresh_tokens WHERE token_hash = ? OR family_id = ?",
                Integer.class, session.refreshToken(), session.refreshToken()))
                .as("the token itself is stored nowhere")
                .isZero();

        Session second = login(member);
        List<StoredToken> both = rowsFor(member);
        assertThat(both).hasSize(2);
        assertThat(both.get(1).familyId()).as("each login is a session of its own").isNotEqualTo(row.familyId());
        assertThat(second.refreshToken()).isNotEqualTo(session.refreshToken());
    }

    // ---------- rotation ----------

    @Test
    void refreshingIssuesANewAccessTokenAndReplacesTheRefreshToken() throws Exception {
        Session login = login(member);

        MvcResult result = refresh(login.refreshToken());
        assertThat(fieldNames(json(result))).containsExactlyInAnyOrder("token", "refreshToken");
        Session refreshed = sessionFrom(result);

        assertThat(refreshed.refreshToken()).isNotEqualTo(login.refreshToken());
        assertThat(jwtService.extractUsername(refreshed.accessToken())).isEqualTo(member.getUsername());
        assertThat(booksWith(refreshed.accessToken())).isEqualTo(200);
        assertThat(booksWith(login.accessToken())).as("the earlier access token is unaffected").isEqualTo(200);

        List<StoredToken> rows = rowsFor(member);
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).revokedAt()).as("the presented token is used up").isNotNull();
        assertThat(rows.get(1).tokenHash()).isEqualTo(sha256(refreshed.refreshToken()));
        assertThat(rows.get(1).revokedAt()).isNull();
        assertThat(rows.get(1).familyId()).as("the same session").isEqualTo(rows.get(0).familyId());
        assertThat(rows.get(1).expiresAt()).as("refreshing never extends a session").isEqualTo(rows.get(0).expiresAt());

        Session again = sessionFrom(refresh(refreshed.refreshToken()));
        assertThat(booksWith(again.accessToken())).as("and the chain goes on").isEqualTo(200);
    }

    // ---------- reuse ----------

    @Test
    void aRefreshTokenWorksOnceAndReusingItEndsTheSession() throws Exception {
        Session otherDevice = login(member);
        Session login = login(member);
        Session refreshed = sessionFrom(refresh(login.refreshToken()));

        assertRefused("replaying the used token", refresh(login.refreshToken()));
        assertRefused("the token that replaced it, once the replay is seen", refresh(refreshed.refreshToken()));

        String session = rowFor(member, login.refreshToken()).familyId();
        assertThat(rowsFor(member))
                .filteredOn(row -> row.familyId().equals(session))
                .hasSize(2)
                .allSatisfy(row -> assertThat(row.revokedAt()).isNotNull());

        assertThat(status(refresh(otherDevice.refreshToken()))).as("another session is not affected").isEqualTo(200);
    }

    // ---------- logout ----------

    @Test
    void logoutRevokesTheRefreshToken() throws Exception {
        Session otherDevice = login(member);
        Session login = login(member);

        MvcResult result = logout(login.refreshToken());
        assertThat(status(result)).isEqualTo(204);
        assertThat(result.getResponse().getContentAsString()).isEmpty();

        assertThat(rowFor(member, login.refreshToken()).revokedAt()).isNotNull();
        assertRefused("refresh after logout", refresh(login.refreshToken()));

        assertThat(status(logout(login.refreshToken()))).as("logging out twice").isEqualTo(204);
        assertThat(status(logout(randomToken()))).as("a token that never existed gets the same answer").isEqualTo(204);

        assertThat(status(refresh(otherDevice.refreshToken()))).as("another session continues").isEqualTo(200);

        // Unchanged by design: a self-contained access token lasts until it expires.
        assertThat(booksWith(login.accessToken())).isEqualTo(200);
    }

    @Test
    void logoutWithAnEarlierTokenOfTheSessionStillEndsIt() throws Exception {
        Session login = login(member);
        Session refreshed = sessionFrom(refresh(login.refreshToken()));

        assertThat(status(logout(login.refreshToken()))).isEqualTo(204);

        assertRefused("the current token of a session that was logged out", refresh(refreshed.refreshToken()));
    }

    // ---------- expiry ----------

    @Test
    void anExpiredRefreshTokenIsRefused() throws Exception {
        Session login = login(member);
        expire(login.refreshToken());

        assertRefused("expired", refresh(login.refreshToken()));
        assertThat(rowsFor(member)).as("nothing was issued in its place").hasSize(1);

        Session fresh = login(member);
        Session refreshed = sessionFrom(refresh(fresh.refreshToken()));
        expire(refreshed.refreshToken());
        assertRefused("expired after a rotation", refresh(refreshed.refreshToken()));
    }

    // ---------- account status ----------

    @Test
    void aDisabledAccountCannotRefreshAndItsSessionEnds() throws Exception {
        assertAccountChangeEndsTheSession("{\"enabled\":false}", "{\"enabled\":true}");
    }

    @Test
    void aLockedAccountCannotRefreshAndItsSessionEnds() throws Exception {
        assertAccountChangeEndsTheSession("{\"accountNonLocked\":false}", "{\"accountNonLocked\":true}");
    }

    private void assertAccountChangeEndsTheSession(String restrict, String restore) throws Exception {
        Session login = login(member);
        assertThat(status(setStatus(member, restrict, adminToken))).isEqualTo(200);

        assertRefused(restrict, refresh(login.refreshToken()));
        assertThat(rowFor(member, login.refreshToken()).revokedAt()).as("ended, not just refused").isNotNull();

        assertThat(status(setStatus(member, restore, adminToken))).isEqualTo(200);
        assertRefused("the old session stays ended once the account is restored", refresh(login.refreshToken()));

        assertThat(status(refresh(login(member).refreshToken()))).as("a new login works again").isEqualTo(200);
    }

    @Test
    void changingThePasswordEndsEveryRefreshSession() throws Exception {
        Session first = login(member);
        Session second = login(member);
        String newPassword = "step159-changed-password";

        MvcResult changed = mockMvc.perform(post("/api/auth/password")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + second.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.createObjectNode()
                                .put("currentPassword", TEST_PASSWORD)
                                .put("newPassword", newPassword)
                                .toString()))
                .andReturn();
        assertThat(status(changed)).isEqualTo(204);

        assertRefused("a session from another device", refresh(first.refreshToken()));
        assertRefused("the session that changed the password", refresh(second.refreshToken()));

        assertThat(status(refresh(login(member, newPassword).refreshToken()))).isEqualTo(200);
    }

    // ---------- what is and is not a refresh token ----------

    @Test
    void anythingThatIsNotALiveRefreshTokenGetsTheSameAnswer() throws Exception {
        Session login = login(member);

        assertRefused("a token that never existed", refresh(randomToken()));
        assertRefused("not a token at all", refresh("not-a-token"));
        assertRefused("an access token", refresh(login.accessToken()));
        assertThat(booksWith(login.refreshToken())).as("and a refresh token is not an access token").isEqualTo(401);
    }

    @Test
    void refreshAndLogoutNeedNoAccessTokenButCheckTheirInput() throws Exception {
        MvcResult withBadBearer = mockMvc.perform(post("/api/auth/refresh")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer expired-or-garbage")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tokenBody(login(member).refreshToken())))
                .andReturn();
        assertThat(status(withBadBearer)).as("an unusable access token does not get in the way").isEqualTo(200);

        for (String path : List.of("/api/auth/refresh", "/api/auth/logout")) {
            assertError(mockMvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content("{}")).andReturn(),
                    400, "Refresh token is required");
            assertError(mockMvc.perform(post(path).contentType(MediaType.APPLICATION_JSON)
                            .content(tokenBody("   "))).andReturn(),
                    400, "Refresh token is required");
            assertError(mockMvc.perform(post(path).contentType(MediaType.APPLICATION_JSON)
                            .content(tokenBody("x".repeat(513)))).andReturn(),
                    400, "Refresh token must not exceed 512 characters");

            assertThat(status(mockMvc.perform(get(path)).andReturn()))
                    .as("only POST is public: %s", path)
                    .isEqualTo(401);
        }
    }

    // ---------- role and library isolation unchanged ----------

    @Test
    void aRefreshedAccessTokenCarriesTheAccountsOwnRoleAndLibrary() throws Exception {
        String memberAccess = sessionFrom(refresh(login(member).refreshToken())).accessToken();
        MvcResult memberAddsBook = mockMvc.perform(post("/api/books")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + memberAccess)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andReturn();
        assertThat(status(memberAddsBook)).as("a member is still not staff").isEqualTo(403);

        String adminAccess = sessionFrom(refresh(login(admin).refreshToken())).accessToken();
        assertThat(status(setStatus(otherLibraryMember, "{\"enabled\":false}", adminAccess)))
                .as("still confined to their own library")
                .isEqualTo(404);
        assertThat(status(setStatus(member, "{\"enabled\":false}", adminAccess))).isEqualTo(200);
    }

    // ---------- nothing sensitive in the log ----------

    @Test
    void noTokenOrTokenHashEverReachesTheLog() throws Exception {
        Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);

        List<String> secrets = new ArrayList<>();
        try {
            Session login = login(member);
            Session refreshed = sessionFrom(refresh(login.refreshToken()));
            refresh(login.refreshToken());
            Session other = login(member);
            logout(other.refreshToken());

            for (Session session : List.of(login, refreshed, other)) {
                secrets.add(session.accessToken());
                secrets.add(session.refreshToken());
                secrets.add(sha256(session.refreshToken()));
            }
        } finally {
            root.detachAppender(appender);
            appender.stop();
        }

        List<String> lines = appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();

        assertThat(lines).as("no token and no token hash in any log line")
                .noneMatch(line -> secrets.stream().anyMatch(line::contains));
        assertThat(lines)
                .as("while what happened is recorded")
                .anyMatch(line -> line.contains("A revoked refresh token was presented"))
                .anyMatch(line -> line.contains("Session ended by logout"));
    }
}
