package com.library.lms.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.PayloadApplicationEvent;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetupTest;
import com.library.lms.entity.Library;
import com.library.lms.entity.Role;
import com.library.lms.entity.User;
import com.library.lms.repository.LibraryRepository;
import com.library.lms.repository.UserRepository;
import com.library.lms.service.PasswordResetIssuingQueue;
import com.library.lms.service.PasswordResetRequested;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.mail.internet.MimeMessage;

/**
 * The reset link as it actually arrives: through SMTP, to a server running in
 * this JVM - and what happens when that server is not there.
 *
 * <p><b>Why a real server rather than a mocked sender.</b> What matters is what
 * the account holder receives: the address it went to, and a link that still
 * works when it gets there. A mock would only show that {@code send} was
 * called. GreenMail listens on a throwaway port and starts afresh for each
 * test; no message leaves the machine.</p>
 *
 * <p><b>The failing half stops that server first</b>, so every send is refused
 * at once. Nothing else may change: the request is still answered 202 - the
 * answer goes out before delivery is even attempted - the issued token is still
 * the one that works, and the failure is reported by account id and no more. A
 * reset that cannot be delivered is one the holder asks for again, not a token
 * quietly spent. The token itself then comes from the delivery event, which is
 * the only copy there is when no message arrives.</p>
 *
 * <p><b>A small connection pool, closed as soon as this class is done.</b> A
 * cached context holds its pool open for the rest of the suite, and the server
 * these tests run against has a limit on connections that the suite as a whole
 * is already close to. No other class shares these settings, so nothing is lost
 * by not caching this one, and the classes that run afterwards see the same
 * headroom they saw before it existed.</p>
 *
 * <p><b>Addresses are unique per test</b>, because the request limit is kept in
 * memory for the life of the context and would otherwise carry over.</p>
 *
 * <p><b>Isolation:</b> the throwaway schema the other integration tests use,
 * never the development database.</p>
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:mysql://localhost:3306/library_db_step131_it"
                + "?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true"
                + "&serverTimezone=UTC",
        "spring.datasource.hikari.maximum-pool-size=2",
        "spring.jpa.hibernate.ddl-auto=update",
        "spring.jpa.open-in-view=false",
        "spring.mail.host=127.0.0.1",
        "spring.mail.port=3025",
        "spring.mail.properties.mail.smtp.starttls.enable=false",
        "spring.mail.properties.mail.smtp.connectiontimeout=2000",
        "spring.mail.properties.mail.smtp.timeout=2000",
        "app.mail.from=library@example.invalid",
        "app.reset-link-base-url=https://library.example.invalid/reset-password"
})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PasswordResetMailIntegrationTest {

    /** The SMTP server the application is pointed at, on 127.0.0.1:3025, restarted for each test. */
    @RegisterExtension
    static final GreenMailExtension GREEN_MAIL = new GreenMailExtension(ServerSetupTest.SMTP)
            .withPerMethodLifecycle(true);

    /** Test-only credentials, never real ones. */
    private static final String OLD_PASSWORD = "mail-test-old-password";

    private static final String NEW_PASSWORD = "mail-test-new-password";

    private static final Pattern TOKEN_IN_LINK = Pattern.compile("token=([^\\s]+)");

    private static String encodedOldPassword;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ConfigurableApplicationContext context;

    @Autowired
    private LibraryRepository libraryRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private PasswordResetIssuingQueue issuingQueue;

    /** What delivery was handed, from whichever thread published it. */
    private final List<PasswordResetRequested> captured = new CopyOnWriteArrayList<>();

    private final ApplicationListener<ApplicationEvent> capture = event -> {
        if (event instanceof PayloadApplicationEvent<?> payload
                && payload.getPayload() instanceof PasswordResetRequested requested) {
            captured.add(requested);
        }
    };

    private String suffix;

    private User member;
    private User disabledMember;

    @BeforeEach
    void createAccountsAndListen() {
        if (encodedOldPassword == null) {
            encodedOldPassword = passwordEncoder.encode(OLD_PASSWORD);
        }
        suffix = UUID.randomUUID().toString().substring(0, 8);

        Library library = new Library();
        library.setName("Mail Library " + suffix);
        library = libraryRepository.save(library);

        member = persistUser(library, "mail-" + suffix + "-member", true);
        disabledMember = persistUser(library, "mail-" + suffix + "-disabled", false);

        context.addApplicationListener(capture);
    }

    @AfterEach
    void stopListening() {
        awaitIssuing();
        context.removeApplicationListener(capture);
    }

    private User persistUser(Library library, String username, boolean enabled) {
        User user = new User();
        user.setUsername(username);
        user.setEmail(username + "@example.invalid");
        user.setPassword(encodedOldPassword);
        user.setRole(Role.ROLE_MEMBER);
        user.setLibrary(library);
        user.setEnabled(enabled);
        return userRepository.save(user);
    }

    private MvcResult forgot(String email) throws Exception {
        String body = objectMapper.createObjectNode().put("email", email).toString();

        return mockMvc.perform(post("/api/auth/forgot-password")
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
    }

    private MvcResult reset(String token, String newPassword) throws Exception {
        String body = objectMapper.createObjectNode()
                .put("token", token)
                .put("newPassword", newPassword)
                .toString();

        return mockMvc.perform(post("/api/auth/reset-password")
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
    }

    private MvcResult login(String username, String password) throws Exception {
        String body = objectMapper.createObjectNode()
                .put("username", username)
                .put("password", password)
                .toString();

        return mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
    }

    private static int status(MvcResult result) {
        return result.getResponse().getStatus();
    }

    /** Waits - up to twenty seconds, polling - until every queued issuing task has run to the end. */
    private void awaitIssuing() {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
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

    /** Asks for a reset and waits for the one message it should produce. */
    private MimeMessage deliveredFor(String email) throws Exception {
        assertThat(status(forgot(email))).isEqualTo(202);
        assertThat(GREEN_MAIL.waitForIncomingEmail(10_000, 1)).as("a message arrives").isTrue();

        MimeMessage[] received = GREEN_MAIL.getReceivedMessages();
        assertThat(received).hasSize(1);
        return received[0];
    }

    /** Asks for a reset with nothing listening, and returns the token the failed delivery was given. */
    private String issuedButUndelivered() throws Exception {
        GREEN_MAIL.stop();

        assertThat(status(forgot(member.getEmail())))
                .as("the answer goes out before delivery is even attempted")
                .isEqualTo(202);
        awaitIssuing();

        assertThat(captured).hasSize(1);
        return captured.get(0).token();
    }

    /** The message's text, decoded from whatever transfer encoding it was sent with. */
    private static String body(MimeMessage message) throws Exception {
        return String.valueOf(message.getContent());
    }

    private static String tokenIn(String messageBody) {
        Matcher matcher = TOKEN_IN_LINK.matcher(messageBody);
        assertThat(matcher.find()).as("the message carries a link with a token").isTrue();

        return URLDecoder.decode(matcher.group(1), StandardCharsets.UTF_8);
    }

    /**
     * This application's own log lines. GreenMail logs the SMTP conversation it
     * is having - envelope, headers and body - which is the message arriving,
     * not something this application wrote down; no such logger exists in a
     * deployment.
     */
    private static List<String> ourLines(ListAppender<ILoggingEvent> appender) {
        return appender.list.stream()
                .filter(event -> event.getLoggerName().startsWith("com.library.lms"))
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    private static ListAppender<ILoggingEvent> captureLogs() {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        ((Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)).addAppender(appender);

        return appender;
    }

    private static void releaseLogs(ListAppender<ILoggingEvent> appender) {
        ((Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)).detachAppender(appender);
        appender.stop();
    }

    // ---------- what arrives ----------

    @Test
    void theLinkIsSentToTheAccountsOwnAddress() throws Exception {
        MimeMessage message = deliveredFor(member.getEmail());

        assertThat(message.getAllRecipients()).hasSize(1);
        assertThat(message.getAllRecipients()[0].toString()).isEqualTo(member.getEmail());
        assertThat(message.getFrom()[0].toString()).isEqualTo("library@example.invalid");
        assertThat(message.getSubject()).isEqualTo("Reset your library account password");
        assertThat(body(message))
                .contains("https://library.example.invalid/reset-password?token=")
                .doesNotContain(member.getUsername())
                .doesNotContain(OLD_PASSWORD)
                .doesNotContain(encodedOldPassword);
    }

    @Test
    void oneRequestSendsOneMessage() throws Exception {
        deliveredFor(member.getEmail());
        awaitIssuing();

        assertThat(GREEN_MAIL.getReceivedMessages()).hasSize(1);
    }

    // ---------- when nothing should be sent ----------

    @Test
    void anAddressWithNoAccountIsNotWrittenTo() throws Exception {
        assertThat(status(forgot("nobody-" + suffix + "@example.invalid"))).isEqualTo(202);
        awaitIssuing();

        assertThat(GREEN_MAIL.waitForIncomingEmail(1_000, 1))
                .as("no account, no message - and the answer was the same 202")
                .isFalse();
    }

    @Test
    void aDisabledAccountIsNotWrittenTo() throws Exception {
        assertThat(status(forgot(disabledMember.getEmail()))).isEqualTo(202);
        awaitIssuing();

        assertThat(GREEN_MAIL.waitForIncomingEmail(1_000, 1)).isFalse();
    }

    // ---------- the log says what happened, and nothing more ----------

    @Test
    void neitherTheLinkNorTheAddressReachesAnyLogLine() throws Exception {
        ListAppender<ILoggingEvent> appender = captureLogs();

        String token;
        try {
            token = tokenIn(body(deliveredFor(member.getEmail())));
            awaitIssuing();
        } finally {
            releaseLogs(appender);
        }

        assertThat(ourLines(appender))
                .anyMatch(line -> line.contains("Password reset link sent for user id=" + member.getId()))
                .allSatisfy(line -> assertThat(line)
                        .doesNotContain(token)
                        .doesNotContain(member.getEmail())
                        .doesNotContain("reset-password?token="));
    }

    // ---------- the whole way round ----------

    @Test
    void theEmailedLinkSetsTheNewPassword() throws Exception {
        String token = tokenIn(body(deliveredFor(member.getEmail())));

        assertThat(status(reset(token, NEW_PASSWORD))).as("the token arrived intact").isEqualTo(204);
        assertThat(status(login(member.getUsername(), NEW_PASSWORD))).isEqualTo(200);
        assertThat(status(login(member.getUsername(), OLD_PASSWORD))).isEqualTo(401);
    }

    // ---------- when the mail server is not there ----------

    @Test
    void anUndeliverableResetStillAnswersTheSame202() throws Exception {
        GREEN_MAIL.stop();

        assertThat(status(forgot(member.getEmail()))).isEqualTo(202);
        awaitIssuing();
    }

    @Test
    void theTokenSurvivesTheFailedSendAndStillSetsThePassword() throws Exception {
        String token = issuedButUndelivered();

        assertThat(status(reset(token, NEW_PASSWORD)))
                .as("a send that failed spends nothing")
                .isEqualTo(204);
        assertThat(status(login(member.getUsername(), NEW_PASSWORD))).isEqualTo(200);
    }

    @Test
    void theFailureIsReportedByAccountIdAndNothingElse() throws Exception {
        ListAppender<ILoggingEvent> appender = captureLogs();

        String token;
        try {
            token = issuedButUndelivered();
        } finally {
            releaseLogs(appender);
        }

        assertThat(ourLines(appender))
                .anyMatch(line -> line.contains("could not be sent for user id=" + member.getId())
                        && line.contains("the token stands"))
                .allSatisfy(line -> assertThat(line)
                        .doesNotContain(token)
                        .doesNotContain(member.getEmail()));
    }
}
