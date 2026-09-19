package com.library.lms.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.LocalDateTime;
import java.util.ArrayList;
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
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.library.lms.entity.AuditAction;
import com.library.lms.entity.AuditEvent;
import com.library.lms.entity.AuditOutcome;
import com.library.lms.entity.AuditTargetType;
import com.library.lms.entity.Library;
import com.library.lms.entity.Role;
import com.library.lms.entity.User;
import com.library.lms.repository.AuditEventRepository;
import com.library.lms.repository.LibraryRepository;
import com.library.lms.repository.UserRepository;
import com.library.lms.service.AuditService;
import com.library.lms.service.AuditTarget;
import com.library.lms.service.PasswordResetIssuingQueue;
import com.library.lms.service.PasswordResetRequested;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * The audit log over HTTP: which changes to accounts, passwords and libraries
 * are recorded, with whose name, in which library - and what never reaches it.
 *
 * <p><b>Two fresh libraries per test.</b> Library A has an administrator, a
 * librarian and two members; library B an administrator and a member. Each
 * library's audit log starts empty, so every assertion about it is exact
 * however many events the shared schema already holds.</p>
 *
 * <p><b>Refusals are recorded even though they roll back.</b> Several tests
 * make the service refuse - and roll back - and then find the refusal
 * recorded; that is the separate transaction at work.</p>
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
class AuditLoggingIntegrationTest {

    /** Test-only credentials, never real ones. */
    private static final String PASSWORD = "audit-test-only-password";

    private static final String NEW_PASSWORD = "audit-test-new-password-3e1";

    private static String encodedPassword;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private LibraryRepository libraryRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AuditEventRepository auditEventRepository;

    @Autowired
    private AuditService auditService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private ConfigurableApplicationContext context;

    @Autowired
    private PasswordResetIssuingQueue issuingQueue;

    private final List<PasswordResetRequested> issued = new CopyOnWriteArrayList<>();

    private final ApplicationListener<ApplicationEvent> capture = event -> {
        if (event instanceof PayloadApplicationEvent<?> payload
                && payload.getPayload() instanceof PasswordResetRequested requested) {
            issued.add(requested);
        }
    };

    private String suffix;
    private Library libraryA;
    private Library libraryB;
    private User adminA;
    private User librarianA;
    private User memberA;
    private User disabledMemberA;
    private User adminB;

    private String adminAToken;
    private String librarianAToken;
    private String memberAToken;
    private String adminBToken;

    /** Every secret this test handles, so none can be found where it must not be. */
    private final List<String> secrets = new ArrayList<>();

    // ---------- fixtures ----------

    @BeforeEach
    void createTwoLibraries() throws Exception {
        context.addApplicationListener(capture);
        if (encodedPassword == null) {
            encodedPassword = passwordEncoder.encode(PASSWORD);
        }
        suffix = UUID.randomUUID().toString().substring(0, 8);

        libraryA = newLibrary("A");
        adminA = persistUser(libraryA, "a-admin", Role.ROLE_ADMIN, true);
        librarianA = persistUser(libraryA, "a-librarian", Role.ROLE_LIBRARIAN, true);
        memberA = persistUser(libraryA, "a-member", Role.ROLE_MEMBER, true);
        disabledMemberA = persistUser(libraryA, "a-disabled", Role.ROLE_MEMBER, false);

        libraryB = newLibrary("B");
        adminB = persistUser(libraryB, "b-admin", Role.ROLE_ADMIN, true);
        persistUser(libraryB, "b-member", Role.ROLE_MEMBER, true);

        adminAToken = token(adminA);
        librarianAToken = token(librarianA);
        memberAToken = token(memberA);
        adminBToken = token(adminB);

        secrets.addAll(List.of(PASSWORD, NEW_PASSWORD, encodedPassword, adminAToken, librarianAToken, memberAToken));
    }

    @AfterEach
    void stopListening() {
        awaitIssuing();
        context.removeApplicationListener(capture);
    }

    private Library newLibrary(String label) {
        Library library = new Library();
        library.setName("Audit Library " + label + " " + suffix);
        return libraryRepository.save(library);
    }

    private User persistUser(Library library, String label, Role role, boolean enabled) {
        User user = new User();
        user.setUsername("audit-" + suffix + "-" + label);
        user.setEmail("audit-" + suffix + "-" + label + "@example.invalid");
        user.setPassword(encodedPassword);
        user.setRole(role);
        user.setLibrary(library);
        user.setEnabled(enabled);
        return userRepository.save(user);
    }

    // ---------- helpers ----------

    private JsonNode login(User user, String password) throws Exception {
        String body = objectMapper.createObjectNode()
                .put("username", user.getUsername())
                .put("password", password)
                .toString();
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
        assertThat(result.getResponse().getStatus()).as("login for %s", user.getUsername()).isEqualTo(200);
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private String token(User user) throws Exception {
        return login(user, PASSWORD).path("token").asText();
    }

    private MvcResult perform(MockHttpServletRequestBuilder request, String token, String body) throws Exception {
        if (token != null) {
            request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mockMvc.perform(request).andReturn();
    }

    private static int status(MvcResult result) {
        return result.getResponse().getStatus();
    }

    /** A library's audit log, oldest first - read the only way the application can: by library. */
    private List<AuditEvent> events(Library library) {
        return auditEventRepository.findByLibraryId(library.getId(), PageRequest.of(0, 200, Sort.by("id")))
                .getContent();
    }

    private static void assertEvent(AuditEvent event, AuditAction action, AuditOutcome outcome, User actor,
            AuditTargetType targetType, Long targetId) {
        assertThat(event.getAction()).isEqualTo(action);
        assertThat(event.getOutcome()).isEqualTo(outcome);
        assertThat(event.getActorUserId()).isEqualTo(actor == null ? null : actor.getId());
        assertThat(event.getTargetType()).isEqualTo(targetType);
        assertThat(event.getTargetId()).isEqualTo(targetId);
        assertThat(event.getOccurredAt()).isBetween(LocalDateTime.now().minusMinutes(5), LocalDateTime.now());
    }

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

    private MvcResult forgot(String email) throws Exception {
        MvcResult result = perform(post("/api/auth/forgot-password"), null,
                objectMapper.createObjectNode().put("email", email).toString());
        awaitIssuing();
        return result;
    }

    private MvcResult resetWithToken(String token, String newPassword) throws Exception {
        return perform(post("/api/auth/reset-password"), null,
                objectMapper.createObjectNode().put("token", token).put("newPassword", newPassword).toString());
    }

    // ---------- accounts ----------

    @Test
    void aCreatedAccountIsRecordedWithItsCreatorTargetAndLibrary() throws Exception {
        String body = objectMapper.createObjectNode()
                .put("username", "audit-" + suffix + "-created")
                .put("email", "audit-" + suffix + "-created@example.invalid")
                .put("password", PASSWORD)
                .put("role", "ROLE_MEMBER")
                .toString();

        MvcResult created = perform(post("/api/users"), adminAToken, body);

        assertThat(status(created)).isEqualTo(201);
        long newId = objectMapper.readTree(created.getResponse().getContentAsString()).path("id").asLong();
        assertThat(events(libraryA)).singleElement().satisfies(event ->
                assertEvent(event, AuditAction.USER_CREATED, AuditOutcome.SUCCESS, adminA, AuditTargetType.USER,
                        newId));
    }

    @Test
    void anAttemptToCreateAnAdministratorIsRecordedAsARefusal() throws Exception {
        String body = objectMapper.createObjectNode()
                .put("username", "audit-" + suffix + "-escalation")
                .put("email", "audit-" + suffix + "-escalation@example.invalid")
                .put("password", PASSWORD)
                .put("role", "ROLE_ADMIN")
                .toString();

        assertThat(status(perform(post("/api/users"), adminAToken, body))).isEqualTo(400);

        assertThat(events(libraryA)).singleElement().satisfies(event ->
                assertEvent(event, AuditAction.USER_CREATED, AuditOutcome.FAILURE, adminA, null, null));
        assertThat(userRepository.findByUsername("audit-" + suffix + "-escalation")).isEmpty();
    }

    @Test
    void aStatusChangeIsRecordedAndARefusedSelfLockoutSurvivesItsRollback() throws Exception {
        String disable = objectMapper.createObjectNode().put("enabled", false).toString();

        assertThat(status(perform(patch("/api/users/{id}/status", memberA.getId()), adminAToken, disable)))
                .isEqualTo(200);
        assertThat(status(perform(patch("/api/users/{id}/status", adminA.getId()), adminAToken, disable)))
                .as("an administrator cannot lock themselves out")
                .isEqualTo(400);

        List<AuditEvent> events = events(libraryA);
        assertThat(events).hasSize(2);
        assertEvent(events.get(0), AuditAction.USER_STATUS_CHANGED, AuditOutcome.SUCCESS, adminA,
                AuditTargetType.USER, memberA.getId());
        assertEvent(events.get(1), AuditAction.USER_STATUS_CHANGED, AuditOutcome.FAILURE, adminA,
                AuditTargetType.USER, adminA.getId());
        assertThat(userRepository.findById(adminA.getId()).orElseThrow().isEnabled())
                .as("the refusal itself was rolled back")
                .isTrue();
    }

    // ---------- passwords ----------

    @Test
    void anOwnPasswordChangeIsRecordedAndAWrongCurrentPasswordToo() throws Exception {
        String wrong = objectMapper.createObjectNode()
                .put("currentPassword", "not-the-current-password")
                .put("newPassword", NEW_PASSWORD)
                .toString();
        String right = objectMapper.createObjectNode()
                .put("currentPassword", PASSWORD)
                .put("newPassword", NEW_PASSWORD)
                .toString();

        assertThat(status(perform(post("/api/auth/password"), memberAToken, wrong))).isEqualTo(400);
        assertThat(status(perform(post("/api/auth/password"), memberAToken, right))).isEqualTo(204);

        List<AuditEvent> events = events(libraryA);
        assertThat(events).hasSize(2);
        assertEvent(events.get(0), AuditAction.PASSWORD_CHANGED, AuditOutcome.FAILURE, memberA,
                AuditTargetType.USER, memberA.getId());
        assertEvent(events.get(1), AuditAction.PASSWORD_CHANGED, AuditOutcome.SUCCESS, memberA,
                AuditTargetType.USER, memberA.getId());
    }

    @Test
    void staffPasswordResetsAreRecordedWithTheirRefusals() throws Exception {
        String body = objectMapper.createObjectNode().put("newPassword", NEW_PASSWORD).toString();

        assertThat(status(perform(post("/api/users/{id}/password-reset", memberA.getId()), librarianAToken, body)))
                .isEqualTo(204);
        assertThat(status(perform(post("/api/users/{id}/password-reset", adminA.getId()), librarianAToken, body)))
                .as("librarians reset members only")
                .isEqualTo(403);
        assertThat(status(perform(post("/api/users/{id}/password-reset", adminA.getId()), adminAToken, body)))
                .as("no self-reset here")
                .isEqualTo(400);
        assertThat(status(perform(post("/api/users/{id}/password-reset", librarianA.getId()), memberAToken, body)))
                .as("a member is stopped by the filter chain, before anything is recorded")
                .isEqualTo(403);

        List<AuditEvent> events = events(libraryA);
        assertThat(events).hasSize(3);
        assertEvent(events.get(0), AuditAction.PASSWORD_RESET_BY_STAFF, AuditOutcome.SUCCESS, librarianA,
                AuditTargetType.USER, memberA.getId());
        assertEvent(events.get(1), AuditAction.PASSWORD_RESET_BY_STAFF, AuditOutcome.FAILURE, librarianA,
                AuditTargetType.USER, adminA.getId());
        assertEvent(events.get(2), AuditAction.PASSWORD_RESET_BY_STAFF, AuditOutcome.FAILURE, adminA,
                AuditTargetType.USER, adminA.getId());
    }

    @Test
    void aSelfServiceResetIsRecordedFromRequestToRedemptionWithNoActor() throws Exception {
        forgot(memberA.getEmail());
        forgot(disabledMemberA.getEmail());
        String token = issued.get(0).token();
        secrets.add(token);

        assertThat(status(resetWithToken(token, NEW_PASSWORD))).isEqualTo(204);
        assertThat(status(resetWithToken(token, "a-reused-token-password"))).isEqualTo(400);

        List<AuditEvent> events = events(libraryA);
        assertThat(events).hasSize(4);
        assertEvent(events.get(0), AuditAction.PASSWORD_RESET_REQUESTED, AuditOutcome.SUCCESS, null,
                AuditTargetType.USER, memberA.getId());
        assertEvent(events.get(1), AuditAction.PASSWORD_RESET_REQUESTED, AuditOutcome.FAILURE, null,
                AuditTargetType.USER, disabledMemberA.getId());
        assertEvent(events.get(2), AuditAction.PASSWORD_RESET_COMPLETED, AuditOutcome.SUCCESS, null,
                AuditTargetType.USER, memberA.getId());
        assertEvent(events.get(3), AuditAction.PASSWORD_RESET_COMPLETED, AuditOutcome.FAILURE, null,
                AuditTargetType.USER, memberA.getId());
    }

    @Test
    void anAddressOrTokenThatNamesNoAccountIsNotRecordedAnywhere() throws Exception {
        long before = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM audit_events", Long.class);

        forgot("nobody-" + suffix + "@example.invalid");
        assertThat(status(resetWithToken("never-issued-" + suffix, NEW_PASSWORD))).isEqualTo(400);

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM audit_events", Long.class))
                .as("no library to scope such an event to, so none is stored")
                .isEqualTo(before);
    }

    // ---------- libraries ----------

    @Test
    void aNewLibraryIsRecordedInItsCreatorsLibrary() throws Exception {
        String body = objectMapper.createObjectNode()
                .put("name", "Audit Library New " + suffix)
                .set("admin", objectMapper.createObjectNode()
                        .put("username", "audit-" + suffix + "-new-admin")
                        .put("email", "audit-" + suffix + "-new-admin@example.invalid")
                        .put("password", PASSWORD))
                .toString();

        MvcResult created = perform(post("/api/libraries"), adminAToken, body);

        assertThat(status(created)).isEqualTo(201);
        long newLibraryId = objectMapper.readTree(created.getResponse().getContentAsString()).path("id").asLong();
        assertThat(events(libraryA)).singleElement().satisfies(event ->
                assertEvent(event, AuditAction.LIBRARY_CREATED, AuditOutcome.SUCCESS, adminA,
                        AuditTargetType.LIBRARY, newLibraryId));
        assertThat(auditEventRepository.findByLibraryId(newLibraryId, PageRequest.of(0, 10)).getContent())
                .as("the new library's own log starts empty")
                .isEmpty();
    }

    // ---------- library isolation ----------

    @Test
    void eachLibrarySeesOnlyItsOwnEvents() throws Exception {
        String disable = objectMapper.createObjectNode().put("enabled", false).toString();
        User memberB = userRepository.findByUsername("audit-" + suffix + "-b-member").orElseThrow();

        perform(patch("/api/users/{id}/status", memberA.getId()), adminAToken, disable);
        perform(patch("/api/users/{id}/status", memberB.getId()), adminBToken, disable);

        assertThat(events(libraryA)).singleElement().satisfies(event -> {
            assertThat(event.getActorUserId()).isEqualTo(adminA.getId());
            assertThat(event.getTargetId()).isEqualTo(memberA.getId());
        });
        assertThat(events(libraryB)).singleElement().satisfies(event -> {
            assertThat(event.getActorUserId()).isEqualTo(adminB.getId());
            assertThat(event.getTargetId()).isEqualTo(memberB.getId());
        });
        assertThat(auditEventRepository.findByLibraryIdAndTargetTypeAndTargetIdOrderByIdAsc(libraryA.getId(),
                AuditTargetType.USER, memberB.getId()))
                .as("library B's account, looked for from library A")
                .isEmpty();
    }

    @Test
    void anAdministratorOfAnotherLibraryCannotLeaveEventsInThisOne() throws Exception {
        String disable = objectMapper.createObjectNode().put("enabled", false).toString();

        assertThat(status(perform(patch("/api/users/{id}/status", memberA.getId()), adminBToken, disable)))
                .as("library A's member, from library B")
                .isEqualTo(404);

        assertThat(events(libraryA)).isEmpty();
        assertThat(events(libraryB)).isEmpty();
    }

    // ---------- reads are not audited ----------

    @Test
    void readingIsNotRecorded() throws Exception {
        perform(get("/api/users"), adminAToken, null);
        perform(get("/api/users/{id}", memberA.getId()), adminAToken, null);
        perform(get("/api/books"), memberAToken, null);

        assertThat(events(libraryA)).isEmpty();
    }

    // ---------- transactions ----------

    @Test
    void aSuccessIsRolledBackWithTheOperationItDescribes() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            auditService.recordSuccess(AuditAction.USER_CREATED, libraryA.getId(), adminA.getId(),
                    AuditTarget.user(memberA.getId()));
            status.setRollbackOnly();
        });

        assertThat(events(libraryA)).isEmpty();
    }

    @Test
    void aSuccessCannotBeRecordedOutsideTheOperationsTransaction() {
        assertThatThrownBy(() -> auditService.recordSuccess(AuditAction.USER_CREATED, libraryA.getId(),
                adminA.getId(), AuditTarget.user(memberA.getId())))
                .isInstanceOf(IllegalTransactionStateException.class);

        assertThat(events(libraryA)).isEmpty();
    }

    @Test
    void aFailureSurvivesTheRollbackOfTheOperationAroundIt() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            auditService.recordFailure(AuditAction.USER_STATUS_CHANGED, libraryA.getId(), adminA.getId(),
                    AuditTarget.user(adminA.getId()));
            status.setRollbackOnly();
        });

        assertThat(events(libraryA)).singleElement()
                .satisfies(event -> assertThat(event.getOutcome()).isEqualTo(AuditOutcome.FAILURE));
    }

    // ---------- no secret can reach the audit log ----------

    @Test
    void theTableHasNoColumnThatCouldHoldText() {
        List<Map<String, Object>> columns = jdbcTemplate.queryForList(
                "SELECT column_name, data_type FROM information_schema.columns"
                        + " WHERE table_schema = DATABASE() AND table_name = 'audit_events'");

        assertThat(columns).extracting(column -> String.valueOf(column.get("COLUMN_NAME")).toLowerCase())
                .containsExactlyInAnyOrder("id", "library_id", "actor_user_id", "action", "target_type",
                        "target_id", "outcome", "occurred_at");
        assertThat(columns).extracting(column -> String.valueOf(column.get("DATA_TYPE")).toLowerCase())
                .as("ids, ENUMs and a time - no CHAR, VARCHAR, TEXT or BLOB")
                .allMatch(type -> type.equals("bigint") || type.equals("enum") || type.equals("datetime"));
    }

    @Test
    void noSecretReachesTheAuditLogOrTheApplicationLog() throws Exception {
        Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);

        try {
            JsonNode session = login(memberA, PASSWORD);
            secrets.add(session.path("refreshToken").asText());
            perform(post("/api/users/{id}/password-reset", memberA.getId()), adminAToken,
                    objectMapper.createObjectNode().put("newPassword", NEW_PASSWORD).toString());
            forgot(memberA.getEmail());
            String token = issued.get(0).token();
            secrets.add(token);
            resetWithToken(token, NEW_PASSWORD + "-again");
            secrets.add(NEW_PASSWORD + "-again");
        } finally {
            root.detachAppender(appender);
            appender.stop();
        }

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT * FROM audit_events WHERE library_id = ?", libraryA.getId());
        List<String> lines = appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();

        assertThat(rows).as("the operations were audited").hasSizeGreaterThanOrEqualTo(3);
        for (String secret : secrets) {
            assertThat(rows).as("audit rows").allSatisfy(row -> assertThat(row.values())
                    .allSatisfy(value -> assertThat(String.valueOf(value)).doesNotContain(secret)));
            assertThat(lines).as("log lines").allSatisfy(line -> assertThat(line).doesNotContain(secret));
        }
        assertThat(appender.list).extracting(ILoggingEvent::getLoggerName)
                .as("AuditService itself writes nothing to the log")
                .noneMatch(name -> name.endsWith(".AuditService"));
    }
}
