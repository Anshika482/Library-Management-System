package com.library.lms.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.library.lms.entity.Library;
import com.library.lms.entity.Role;
import com.library.lms.entity.User;
import com.library.lms.repository.LibraryRepository;
import com.library.lms.repository.UserRepository;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * Drives the login endpoint until it starts refusing, and checks that the
 * refusal is indistinguishable from any other failed login.
 *
 * <p>The limit and the block are configured down to three attempts and one
 * second for this class. The rules themselves - counting, expiry, cleanup - are
 * pinned deterministically by {@code LoginAttemptServiceTest} against a fake
 * clock; what this class adds is the wiring: that the controller consults the
 * limiter before checking a password, that a blocked caller is told exactly
 * what a wrong password is told, and that nothing about the block reaches the
 * response. Only one test here waits on the real clock, and it waits a little
 * over a second.</p>
 *
 * <p><b>Every refusal is compared field by field.</b> A wrong password, an
 * unknown username and a blocked username must produce the same status and the
 * same message; otherwise the endpoint tells an attacker which usernames are
 * worth attacking and when to come back.</p>
 *
 * <p><b>Isolation:</b> the throwaway schema the other integration tests use,
 * never the development database. Every test creates users with a fresh unique
 * suffix, so one test's blocked username cannot affect another's.</p>
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:mysql://localhost:3306/library_db_step129_it"
                + "?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true"
                + "&serverTimezone=UTC",
        "spring.jpa.hibernate.ddl-auto=update",
        "spring.jpa.open-in-view=false",
        "security.login.max-failed-attempts=3",
        "security.login.block-duration=PT1S"
})
@AutoConfigureMockMvc
class LoginRateLimitIntegrationTest {

    /** Test-only credential, never a real one, and never reused outside this class. */
    private static final String TEST_PASSWORD = "step144-test-only-password";

    /** Matches {@code security.login.max-failed-attempts} above. */
    private static final int MAX_FAILED_ATTEMPTS = 3;

    private static final String WRONG_PASSWORD = "step144-wrong-password";

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

    private String suffix;
    private User member;
    private User other;

    // ---------- fixtures ----------

    @BeforeEach
    void createUsers() {
        suffix = UUID.randomUUID().toString().substring(0, 8);

        Library library = new Library();
        library.setName("Step144 Library " + suffix);
        library = libraryRepository.save(library);

        member = persistUser(library, "member");
        other = persistUser(library, "other");
    }

    private User persistUser(Library library, String label) {
        String username = "step144-" + label + "-" + suffix;

        User user = new User();
        user.setUsername(username);
        user.setEmail(username + "@example.invalid");
        user.setPassword(passwordEncoder.encode(TEST_PASSWORD));
        user.setFullName("Step144 " + label);
        user.setRole(Role.ROLE_MEMBER);
        user.setLibrary(library);
        return userRepository.save(user);
    }

    // ---------- helpers ----------

    private MvcResult login(String username, String password) throws Exception {
        String body = objectMapper.createObjectNode()
                .put("username", username)
                .put("password", password)
                .toString();

        return mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private static int status(MvcResult result) {
        return result.getResponse().getStatus();
    }

    private String message(MvcResult result) throws Exception {
        return json(result).path("message").asText();
    }

    /** Status and message together - everything a caller can tell apart. */
    private String signature(MvcResult result) throws Exception {
        return status(result) + " " + message(result);
    }

    private void failLogin(String username, int times) throws Exception {
        for (int i = 0; i < times; i++) {
            assertThat(status(login(username, WRONG_PASSWORD))).isEqualTo(401);
        }
    }

    // ---------- A: the existing behaviour still holds ----------

    @Test
    void aValidLoginStillSucceedsAndItsTokenStillWorks() throws Exception {
        MvcResult result = login(member.getUsername(), TEST_PASSWORD);

        assertThat(status(result)).isEqualTo(200);
        String token = json(result).path("token").asText();
        assertThat(token).isNotBlank();

        assertThat(mockMvc.perform(get("/api/books").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andReturn().getResponse().getStatus())
                .as("the JWT is unchanged by any of this")
                .isEqualTo(200);
    }

    @Test
    void aWrongPasswordAndAnUnknownUsernameGiveTheSameGeneric401() throws Exception {
        MvcResult wrongPassword = login(member.getUsername(), WRONG_PASSWORD);
        MvcResult unknownUser = login("step144-no-such-account-" + suffix, WRONG_PASSWORD);

        assertThat(signature(wrongPassword)).isEqualTo("401 Invalid username or password");
        assertThat(signature(unknownUser)).isEqualTo(signature(wrongPassword));
    }

    // ---------- B: field limits ----------

    @Test
    void anOversizedUsernameIsRejectedBeforeAnythingElseHappens() throws Exception {
        MvcResult result = login("u".repeat(256), TEST_PASSWORD);

        assertThat(status(result)).isEqualTo(400);
        assertThat(message(result)).isEqualTo("Username must not exceed 255 characters");
    }

    @Test
    void anOversizedPasswordIsRejectedAndNeverEchoedBack() throws Exception {
        String password = "step144-oversized-" + "p".repeat(80);

        MvcResult result = login(member.getUsername(), password);

        assertThat(status(result)).isEqualTo(400);
        assertThat(message(result)).isEqualTo("Password must not exceed 72 characters");
        assertThat(result.getResponse().getContentAsString())
                .as("the password must not come back in the reply")
                .doesNotContain(password);
    }

    @Test
    void aRejectedOversizedPasswordIsNeverLogged() throws Exception {
        String password = "step144-oversized-secret-" + "p".repeat(80);

        List<ILoggingEvent> events = captureLogsWhile(() -> login(member.getUsername(), password));

        assertThat(messages(events)).noneMatch(line -> line.contains(password));
    }

    @Test
    void oversizedAttemptsDoNotCountTowardsTheLimit() throws Exception {
        // Validation refuses these before the controller runs, so they never
        // reach the limiter - an oversized body must not be a way to lock
        // somebody else out.
        for (int i = 0; i < MAX_FAILED_ATTEMPTS + 2; i++) {
            assertThat(status(login(member.getUsername(), "p".repeat(80)))).isEqualTo(400);
        }

        assertThat(status(login(member.getUsername(), TEST_PASSWORD))).isEqualTo(200);
    }

    // ---------- C: rate limiting ----------

    @Test
    void repeatedFailuresEventuallyRefuseEvenTheCorrectPassword() throws Exception {
        failLogin(member.getUsername(), MAX_FAILED_ATTEMPTS);

        MvcResult blocked = login(member.getUsername(), TEST_PASSWORD);

        assertThat(status(blocked)).as("the right password is refused while blocked").isEqualTo(401);
        assertThat(signature(blocked)).isEqualTo("401 Invalid username or password");
    }

    @Test
    void aBlockedUsernameIsIndistinguishableFromEveryOtherFailure() throws Exception {
        failLogin(member.getUsername(), MAX_FAILED_ATTEMPTS);

        String blocked = signature(login(member.getUsername(), TEST_PASSWORD));
        String wrongPassword = signature(login(other.getUsername(), WRONG_PASSWORD));
        String unknownUser = signature(login("step144-nobody-" + suffix, WRONG_PASSWORD));

        assertThat(blocked).isEqualTo(wrongPassword).isEqualTo(unknownUser);
    }

    @Test
    void nothingAboutTheBlockReachesTheCaller() throws Exception {
        failLogin(member.getUsername(), MAX_FAILED_ATTEMPTS);

        MvcResult blocked = login(member.getUsername(), TEST_PASSWORD);
        JsonNode body = json(blocked);
        List<String> fields = new java.util.ArrayList<>();
        body.fieldNames().forEachRemaining(fields::add);

        assertThat(fields).containsExactlyInAnyOrder("status", "message", "timestamp");
        assertThat(blocked.getResponse().getContentAsString().toLowerCase())
                .doesNotContain("block").doesNotContain("attempt").doesNotContain("rate")
                .doesNotContain("retry").doesNotContain("lock");
        assertThat(blocked.getResponse().getHeaderNames())
                .as("no Retry-After either - it would say how long to wait")
                .doesNotContain("Retry-After");
    }

    @Test
    void aSuccessfulLoginClearsTheCount() throws Exception {
        failLogin(member.getUsername(), MAX_FAILED_ATTEMPTS - 1);

        assertThat(status(login(member.getUsername(), TEST_PASSWORD))).isEqualTo(200);

        // Back to a full allowance: this many failures would have blocked the
        // username if the earlier ones still counted.
        failLogin(member.getUsername(), MAX_FAILED_ATTEMPTS - 1);

        assertThat(status(login(member.getUsername(), TEST_PASSWORD))).isEqualTo(200);
    }

    @Test
    void theBlockExpiresOnItsOwn() throws Exception {
        failLogin(member.getUsername(), MAX_FAILED_ATTEMPTS);
        assertThat(status(login(member.getUsername(), TEST_PASSWORD))).isEqualTo(401);

        // security.login.block-duration is PT1S for this class.
        Thread.sleep(1_200);

        assertThat(status(login(member.getUsername(), TEST_PASSWORD)))
                .as("temporary, and it clears itself")
                .isEqualTo(200);
    }

    @Test
    void oneBlockedUsernameDoesNotStopAnybodyElseLoggingIn() throws Exception {
        failLogin(member.getUsername(), MAX_FAILED_ATTEMPTS);
        assertThat(status(login(member.getUsername(), TEST_PASSWORD))).isEqualTo(401);

        assertThat(status(login(other.getUsername(), TEST_PASSWORD)))
                .as("no global lock")
                .isEqualTo(200);
    }

    // ---------- D: nothing sensitive is written down ----------

    @Test
    void noPasswordOrTokenIsEverLogged() throws Exception {
        List<ILoggingEvent> events = captureLogsWhile(() -> {
            login(member.getUsername(), WRONG_PASSWORD);
            failLogin(member.getUsername(), MAX_FAILED_ATTEMPTS - 1);
            login(member.getUsername(), TEST_PASSWORD);
            login(other.getUsername(), TEST_PASSWORD);
        });

        String token = json(login(other.getUsername(), TEST_PASSWORD)).path("token").asText();

        assertThat(messages(events))
                .as("neither the right password nor the wrong one")
                .noneMatch(line -> line.contains(TEST_PASSWORD) || line.contains(WRONG_PASSWORD));
        assertThat(messages(events))
                .as("nor any issued token")
                .noneMatch(line -> line.contains(token));
        assertThat(messages(events))
                .as("the refusal itself is recorded, so an operator can see it")
                .anyMatch(line -> line.contains("Login refused by rate limit"));
    }

    private List<ILoggingEvent> captureLogsWhile(ThrowingRunnable action) throws Exception {
        Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);

        try {
            action.run();
        } finally {
            root.detachAppender(appender);
            appender.stop();
        }

        return List.copyOf(appender.list);
    }

    private static List<String> messages(List<ILoggingEvent> events) {
        return events.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
