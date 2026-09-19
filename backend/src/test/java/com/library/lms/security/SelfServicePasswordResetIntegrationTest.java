package com.library.lms.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.PayloadApplicationEvent;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.library.lms.entity.Library;
import com.library.lms.entity.PasswordResetToken;
import com.library.lms.entity.Role;
import com.library.lms.entity.User;
import com.library.lms.repository.LibraryRepository;
import com.library.lms.repository.PasswordResetTokenRepository;
import com.library.lms.repository.UserRepository;
import com.library.lms.service.PasswordResetIssuingQueue;
import com.library.lms.service.PasswordResetRequested;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * Self-service password reset over HTTP: asking for a token, redeeming it, and
 * everything neither answer may reveal.
 *
 * <p><b>The token comes from the delivery event.</b> No email is sent; a
 * {@link PasswordResetRequested} carries the one copy of the token, and a
 * listener this test registers reads it the way a mail sender would. That is
 * also how "no token was issued" is observed: the HTTP answer is identical
 * either way, so the event is the only difference.</p>
 *
 * <p><b>Issuing happens after the answer</b>, on the issuing queue's own
 * thread. So before reading what was issued, the test waits until the queue
 * has finished everything handed to it - and one test holds the account's row
 * locked to show that the answer does not wait for that work at all.</p>
 *
 * <p><b>Addresses are unique per test</b>, because the request limit is kept in
 * memory for the life of the context and would otherwise carry over.</p>
 *
 * <p><b>Isolation:</b> the throwaway schema the other integration tests use,
 * never the development database.</p>
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:mysql://localhost:3306/library_db_step129_it"
                + "?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true"
                + "&serverTimezone=UTC",
        "spring.jpa.hibernate.ddl-auto=update",
        "spring.jpa.open-in-view=false"
})
@AutoConfigureMockMvc
class SelfServicePasswordResetIntegrationTest {

    /** Test-only credentials, never real ones. */
    private static final String OLD_PASSWORD = "forgot-test-old-password";

    private static final String NEW_PASSWORD = "forgot-test-new-password-91c";

    private static final String REFUSAL = "Invalid or expired password reset token.";

    private static String encodedOldPassword;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ConfigurableApplicationContext context;

    @Autowired
    private PasswordResetIssuingQueue issuingQueue;

    /** What delivery would have received, from whichever thread published it. */
    private final List<PasswordResetRequested> captured = new CopyOnWriteArrayList<>();

    private final ApplicationListener<ApplicationEvent> capture = event -> {
        if (event instanceof PayloadApplicationEvent<?> payload
                && payload.getPayload() instanceof PasswordResetRequested requested) {
            captured.add(requested);
        }
    };

    @Autowired
    private LibraryRepository libraryRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordResetTokenRepository tokenRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private String suffix;
    private User member;
    private User disabledMember;
    private User lockedMember;

    // ---------- fixtures ----------

    @BeforeEach
    void listenForIssuedTokens() {
        context.addApplicationListener(capture);
    }

    @AfterEach
    void stopListening() {
        awaitIssuing();
        context.removeApplicationListener(capture);
    }

    @BeforeEach
    void createAccounts() {
        if (encodedOldPassword == null) {
            encodedOldPassword = passwordEncoder.encode(OLD_PASSWORD);
        }
        suffix = UUID.randomUUID().toString().substring(0, 8);

        Library library = new Library();
        library.setName("Forgot Library " + suffix);
        library = libraryRepository.save(library);

        member = persistUser(library, "member", true, true);
        disabledMember = persistUser(library, "disabled", false, true);
        lockedMember = persistUser(library, "locked", true, false);
    }

    private User persistUser(Library library, String label, boolean enabled, boolean accountNonLocked) {
        User user = new User();
        user.setUsername("forgot-" + suffix + "-" + label);
        user.setEmail("forgot-" + label + "-" + suffix + "@example.invalid");
        user.setPassword(encodedOldPassword);
        user.setRole(Role.ROLE_MEMBER);
        user.setLibrary(library);
        user.setEnabled(enabled);
        user.setAccountNonLocked(accountNonLocked);
        return userRepository.save(user);
    }

    // ---------- helpers ----------

    private MvcResult forgot(String email) throws Exception {
        String body = objectMapper.createObjectNode().put("email", email).toString();
        return mockMvc.perform(post("/api/auth/forgot-password").contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn();
    }

    private MvcResult reset(String token, String newPassword) throws Exception {
        String body = objectMapper.createObjectNode().put("token", token).put("newPassword", newPassword).toString();
        return mockMvc.perform(post("/api/auth/reset-password").contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn();
    }

    private MvcResult login(User user, String password) throws Exception {
        String body = objectMapper.createObjectNode()
                .put("username", user.getUsername())
                .put("password", password)
                .toString();
        return mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn();
    }

    private MvcResult refresh(String refreshToken) throws Exception {
        String body = objectMapper.createObjectNode().put("refreshToken", refreshToken).toString();
        return mockMvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn();
    }

    /** Every token issued during this test, in order, once the queue has finished everything handed to it. */
    private List<PasswordResetRequested> issued() {
        awaitIssuing();
        return List.copyOf(captured);
    }

    /** Waits - up to ten seconds, polling - until every queued issuing task has run to the end. */
    private void awaitIssuing() {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (issuingQueue.pending() > 0) {
            assertThat(System.nanoTime()).as("the issuing queue drains").isLessThan(deadline);
            try {
                Thread.sleep(5);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
        }
    }

    /** Asks for a reset for the member and returns the token delivery would have sent. */
    private String tokenFor(User user) throws Exception {
        int before = issued().size();
        assertThat(status(forgot(user.getEmail()))).isEqualTo(202);
        assertThat(issued()).hasSize(before + 1);
        return issued().get(before).token();
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private static int status(MvcResult result) {
        return result.getResponse().getStatus();
    }

    private static String body(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString();
    }

    private static String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private void assertPassword(User user, String works, String doesNot) throws Exception {
        assertThat(status(login(user, works))).as("%s signs in", user.getUsername()).isEqualTo(200);
        assertThat(status(login(user, doesNot))).as("and not with the other password").isEqualTo(401);
    }

    // ---------- enumeration protection ----------

    @Test
    void theAnswerIsIdenticalWhetherOrNotTheAddressHasAnAccount() throws Exception {
        MvcResult known = forgot(member.getEmail());
        MvcResult unknown = forgot("nobody-" + suffix + "@example.invalid");
        MvcResult disabled = forgot(disabledMember.getEmail());
        MvcResult locked = forgot(lockedMember.getEmail());

        for (MvcResult result : List.of(known, unknown, disabled, locked)) {
            assertThat(status(result)).isEqualTo(202);
            assertThat(body(result)).as("byte for byte the same body").isEqualTo(body(known));
        }
        assertThat(json(known).path("message").asText()).isNotBlank();
        assertThat(json(known).size()).as("one field, the fixed message").isEqualTo(1);

        assertThat(issued()).as("only the enabled, unlocked account was issued a token")
                .singleElement()
                .satisfies(event -> assertThat(event.userId()).isEqualTo(member.getId()));
    }

    @Test
    void theAddressIsMatchedWhateverItsCase() throws Exception {
        assertThat(status(forgot(member.getEmail().toUpperCase()))).isEqualTo(202);

        assertThat(issued()).singleElement().satisfies(event -> assertThat(event.userId()).isEqualTo(member.getId()));
    }

    @Test
    void anAddressPaddedWithSpacesIsMalformedLikeEverywhereElse() throws Exception {
        MvcResult refused = forgot("  " + member.getEmail() + " ");

        assertThat(status(refused)).as("the same address rule as account creation").isEqualTo(400);
        assertThat(issued()).isEmpty();
    }

    // ---------- timing: the answer does not wait for the account's work ----------

    @Test
    void theAnswerDoesNotWaitForTheAccountToBeLookedUpOrATokenWritten() throws Exception {
        // Hold the member's row locked. Writing a token checks the foreign key
        // against that row, so issuing for the member cannot finish until the
        // lock is released - if the answer waited for issuing, it would wait
        // here too.
        TransactionStatus lock = transactionManager.getTransaction(new DefaultTransactionDefinition());
        MvcResult known;
        MvcResult unknown;
        try {
            jdbcTemplate.queryForObject("SELECT id FROM users WHERE id = ? FOR UPDATE", Long.class, member.getId());

            known = forgot(member.getEmail());
            unknown = forgot("nobody-" + suffix + "@example.invalid");

            assertThat(issuingQueue.pending())
                    .as("the member's token is still waiting behind the lock, after the answer was sent")
                    .isPositive();
            assertThat(captured).as("nothing issued yet").isEmpty();
        } finally {
            transactionManager.rollback(lock);
        }

        assertThat(status(known)).isEqualTo(202);
        assertThat(body(known)).isEqualTo(body(unknown));
        assertThat(issued()).as("released, the work completes")
                .singleElement()
                .satisfies(event -> assertThat(event.userId()).isEqualTo(member.getId()));
    }

    // ---------- the token ----------

    @Test
    void aTokenResetsThePasswordEndToEnd() throws Exception {
        String token = tokenFor(member);

        MvcResult result = reset(token, NEW_PASSWORD);

        assertThat(status(result)).isEqualTo(204);
        assertThat(body(result)).isEmpty();
        assertPassword(member, NEW_PASSWORD, OLD_PASSWORD);
        assertThat(passwordEncoder.matches(NEW_PASSWORD,
                userRepository.findById(member.getId()).orElseThrow().getPassword())).isTrue();
    }

    @Test
    void theTokenIsStoredOnlyAsItsSha256() throws Exception {
        String token = tokenFor(member);

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT * FROM password_reset_tokens WHERE user_id = ?", member.getId());

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("token_hash")).isEqualTo(sha256(token));
        assertThat(rows.get(0).values())
                .as("no column holds the token itself")
                .allSatisfy(value -> assertThat(String.valueOf(value)).doesNotContain(token));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM password_reset_tokens WHERE token_hash = ?", Integer.class, token))
                .isZero();
    }

    @Test
    void aTokenWorksExactlyOnce() throws Exception {
        String token = tokenFor(member);
        assertThat(status(reset(token, NEW_PASSWORD))).isEqualTo(204);

        MvcResult reused = reset(token, "a-second-new-password");

        assertThat(status(reused)).isEqualTo(400);
        assertThat(json(reused).path("message").asText()).isEqualTo(REFUSAL);
        assertPassword(member, NEW_PASSWORD, "a-second-new-password");
    }

    @Test
    void anExpiredTokenIsRefusedAndChangesNothing() throws Exception {
        String token = tokenFor(member);
        String hash = sha256(token);
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            PasswordResetToken stored = tokenRepository.findByTokenHash(hash).orElseThrow();
            stored.setExpiresAt(LocalDateTime.now().minusMinutes(1));
        });

        MvcResult refused = reset(token, NEW_PASSWORD);

        assertThat(status(refused)).isEqualTo(400);
        assertThat(json(refused).path("message").asText()).isEqualTo(REFUSAL);
        assertPassword(member, OLD_PASSWORD, NEW_PASSWORD);
    }

    @Test
    void aNewerRequestSupersedesTheEarlierToken() throws Exception {
        String earlier = tokenFor(member);
        String later = tokenFor(member);

        assertThat(status(reset(earlier, NEW_PASSWORD))).as("the earlier token").isEqualTo(400);
        assertThat(status(reset(later, NEW_PASSWORD))).as("the latest token").isEqualTo(204);
    }

    @Test
    void everyUnusableTokenGetsTheSameAnswer() throws Exception {
        String used = tokenFor(member);
        reset(used, NEW_PASSWORD);

        for (String token : List.of("never-issued-" + suffix, used)) {
            MvcResult refused = reset(token, "yet-another-password");

            assertThat(status(refused)).isEqualTo(400);
            assertThat(json(refused).path("message").asText()).isEqualTo(REFUSAL);
        }
    }

    @Test
    void aTokenOfAnAccountDisabledSinceItWasIssuedIsRefused() throws Exception {
        String token = tokenFor(member);
        User stored = userRepository.findById(member.getId()).orElseThrow();
        stored.setEnabled(false);
        userRepository.save(stored);

        MvcResult refused = reset(token, NEW_PASSWORD);

        assertThat(status(refused)).isEqualTo(400);
        assertThat(json(refused).path("message").asText()).isEqualTo(REFUSAL);
        assertThat(passwordEncoder.matches(OLD_PASSWORD,
                userRepository.findById(member.getId()).orElseThrow().getPassword())).isTrue();
    }

    // ---------- what a reset ends ----------

    @Test
    void aResetEndsEverySessionAndClearsTheLoginBlock() throws Exception {
        String firstDevice = json(login(member, OLD_PASSWORD)).path("refreshToken").asText();
        String secondDevice = json(login(member, OLD_PASSWORD)).path("refreshToken").asText();
        for (int attempt = 0; attempt < 5; attempt++) {
            login(member, "a-wrong-guess-" + attempt);
        }
        assertThat(status(login(member, OLD_PASSWORD))).as("blocked after five failures").isEqualTo(401);

        assertThat(status(reset(tokenFor(member), NEW_PASSWORD))).isEqualTo(204);

        assertThat(status(refresh(firstDevice))).isEqualTo(401);
        assertThat(status(refresh(secondDevice))).isEqualTo(401);
        assertThat(status(login(member, NEW_PASSWORD))).as("the block is cleared").isEqualTo(200);
    }

    // ---------- rate limiting ----------

    @Test
    void requestsBeyondTheLimitAreDroppedWithTheSameAnswer() throws Exception {
        MvcResult first = forgot(member.getEmail());
        forgot(member.getEmail());
        forgot(member.getEmail());
        assertThat(issued()).as("three requests within the limit").hasSize(3);

        MvcResult fourth = forgot(member.getEmail());
        MvcResult fifthInAnotherCase = forgot(member.getEmail().toUpperCase());

        for (MvcResult dropped : List.of(fourth, fifthInAnotherCase)) {
            assertThat(status(dropped)).isEqualTo(202);
            assertThat(body(dropped)).as("indistinguishable from an accepted request").isEqualTo(body(first));
        }
        assertThat(issued()).as("no token for a dropped request").hasSize(3);

        String otherAddress = persistUser(member.getLibrary(), "other", true, true).getEmail();
        forgot(otherAddress);
        assertThat(issued()).as("another address is unaffected").hasSize(4);
    }

    @Test
    void anAddressWithNoAccountIsLimitedExactlyLikeOneWithAnAccount() throws Exception {
        String nobody = "nobody-" + suffix + "@example.invalid";
        List<MvcResult> answers = new java.util.ArrayList<>();
        for (int i = 0; i < 5; i++) {
            answers.add(forgot(nobody));
        }

        assertThat(answers).allSatisfy(answer -> assertThat(status(answer)).isEqualTo(202));
        assertThat(answers).extracting(SelfServicePasswordResetIntegrationTest::body)
                .containsOnly(body(answers.get(0)));
        assertThat(issued()).isEmpty();
    }

    // ---------- validation ----------

    @Test
    void aMalformedRequestIsA400ThatSaysNothingAboutAccounts() throws Exception {
        for (String email : List.of("not-an-address", "", "   ")) {
            MvcResult refused = forgot(email);
            assertThat(status(refused)).as("'%s'", email).isEqualTo(400);
        }
        assertThat(status(mockMvc.perform(post("/api/auth/forgot-password")
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andReturn())).isEqualTo(400);
        assertThat(issued()).isEmpty();
    }

    @Test
    void aNewPasswordThatBreaksTheRuleDoesNotUseTheTokenUp() throws Exception {
        String token = tokenFor(member);

        for (String invalid : List.of("7-chars", "z".repeat(73))) {
            MvcResult refused = reset(token, invalid);
            assertThat(status(refused)).as("%d characters", invalid.length()).isEqualTo(400);
            assertThat(body(refused)).as("never echoed").doesNotContain(invalid);
        }
        assertThat(status(mockMvc.perform(post("/api/auth/reset-password")
                .contentType(MediaType.APPLICATION_JSON).content("{\"newPassword\":\"" + NEW_PASSWORD + "\"}"))
                .andReturn())).as("no token").isEqualTo(400);

        assertThat(status(reset(token, NEW_PASSWORD))).as("the token is still good").isEqualTo(204);
    }

    // ---------- what is unchanged ----------

    @Test
    void theSignedInPasswordChangeIsUnchanged() throws Exception {
        String accessToken = json(login(member, OLD_PASSWORD)).path("token").asText();
        String body = objectMapper.createObjectNode()
                .put("currentPassword", OLD_PASSWORD)
                .put("newPassword", NEW_PASSWORD)
                .toString();

        MvcResult changed = mockMvc.perform(post("/api/auth/password")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();

        assertThat(status(changed)).isEqualTo(204);
        assertPassword(member, NEW_PASSWORD, OLD_PASSWORD);
    }

    // ---------- log hygiene ----------

    @Test
    void noTokenHashAddressOrPasswordReachesAnyLogLine() throws Exception {
        Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);

        String token;
        String nobody = "nobody-" + suffix + "@example.invalid";
        try {
            token = tokenFor(member);
            forgot(nobody);
            forgot(disabledMember.getEmail());
            reset(token, NEW_PASSWORD);
            reset(token, "a-reused-attempt-password");
            reset("never-issued-" + suffix, NEW_PASSWORD);
            awaitIssuing();
        } finally {
            root.detachAppender(appender);
            appender.stop();
        }

        List<String> lines = appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
        String issuedToken = token;
        String hash = sha256(issuedToken);

        assertThat(lines).as("what happened is recorded, by id")
                .anyMatch(line -> line.contains("Password reset token issued for user id=" + member.getId()))
                .anyMatch(line -> line.contains("Password reset completed for user id=" + member.getId()));
        assertThat(lines).allSatisfy(line -> assertThat(line)
                .doesNotContain(issuedToken)
                .doesNotContain(hash)
                .doesNotContain(member.getEmail())
                .doesNotContain(nobody)
                .doesNotContain(disabledMember.getEmail())
                .doesNotContain(NEW_PASSWORD)
                .doesNotContain("a-reused-attempt-password"));
    }
}
