package com.library.lms.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

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

/**
 * {@code GET /api/audit-events}: an administrator reads their own library's
 * audit log, and nobody else reads anything.
 *
 * <p>The log is the record of who changed which account. That makes it worth
 * reading and worth protecting: these tests pin down both locks on it - the
 * filter chain's ADMIN rule and the service's own check - the library it is
 * scoped to, and the fact that no filter, page or sort can widen that scope.</p>
 *
 * <p>Events are appended straight through the repository so their times and
 * actors are known exactly; the last test goes the long way round, making a
 * real change through the API and finding it in the log.</p>
 *
 * <p><b>Isolation:</b> the throwaway schema the other integration tests use,
 * never the development database.</p>
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:mysql://localhost:3306/library_db_step130_it"
                + "?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true"
                + "&serverTimezone=UTC",
        "spring.jpa.hibernate.ddl-auto=update",
        "spring.jpa.open-in-view=false"
})
@AutoConfigureMockMvc
class AuditEventApiIntegrationTest {

    /** Test-only credential, never a real one. */
    private static final String PASSWORD = "audit-read-test-password";

    /**
     * What the filter chain says when a role may not reach the path at all.
     * The service's own refusal reads {@code "Access denied"} without the full
     * stop, so a test can tell which of the two locks answered;
     * {@code AuditServiceTest} covers that one.
     */
    private static final String FILTER_REFUSAL = "Access denied.";

    private static final Set<String> EVENT_FIELDS =
            Set.of("id", "action", "outcome", "actorUserId", "targetType", "targetId", "occurredAt");

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
    private PasswordEncoder passwordEncoder;

    private Library libraryA;
    private Library libraryB;

    private User adminA;
    private User librarianA;
    private User memberA;
    private User adminB;
    private User memberB;

    /** The five events of library A, oldest first. */
    private List<Long> eventsOfA;

    /** The one event of library B. */
    private Long eventOfB;

    private LocalDateTime base;

    @BeforeEach
    void createTwoLibrariesWithLogs() {
        if (encodedPassword == null) {
            encodedPassword = passwordEncoder.encode(PASSWORD);
        }
        String suffix = UUID.randomUUID().toString().substring(0, 8);

        libraryA = persistLibrary("Audit Read Library A " + suffix);
        libraryB = persistLibrary("Audit Read Library B " + suffix);

        adminA = persistUser(libraryA, "audit-" + suffix + "-admin-a", Role.ROLE_ADMIN);
        librarianA = persistUser(libraryA, "audit-" + suffix + "-librarian-a", Role.ROLE_LIBRARIAN);
        memberA = persistUser(libraryA, "audit-" + suffix + "-member-a", Role.ROLE_MEMBER);
        adminB = persistUser(libraryB, "audit-" + suffix + "-admin-b", Role.ROLE_ADMIN);
        memberB = persistUser(libraryB, "audit-" + suffix + "-member-b", Role.ROLE_MEMBER);

        base = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS).minusHours(1);

        eventsOfA = new ArrayList<>();
        eventsOfA.add(append(libraryA, memberA.getId(), AuditAction.PASSWORD_CHANGED, AuditTargetType.USER,
                memberA.getId(), AuditOutcome.SUCCESS, base));
        eventsOfA.add(append(libraryA, adminA.getId(), AuditAction.USER_CREATED, AuditTargetType.USER,
                memberA.getId(), AuditOutcome.SUCCESS, base.plusMinutes(1)));
        eventsOfA.add(append(libraryA, librarianA.getId(), AuditAction.USER_STATUS_CHANGED, AuditTargetType.USER,
                adminA.getId(), AuditOutcome.FAILURE, base.plusMinutes(2)));
        eventsOfA.add(append(libraryA, librarianA.getId(), AuditAction.PASSWORD_RESET_BY_STAFF, AuditTargetType.USER,
                memberA.getId(), AuditOutcome.SUCCESS, base.plusMinutes(3)));
        eventsOfA.add(append(libraryA, adminA.getId(), AuditAction.LIBRARY_CREATED, AuditTargetType.LIBRARY,
                libraryA.getId(), AuditOutcome.SUCCESS, base.plusMinutes(4)));

        eventOfB = append(libraryB, adminB.getId(), AuditAction.USER_CREATED, AuditTargetType.USER,
                memberB.getId(), AuditOutcome.SUCCESS, base.plusMinutes(2));
    }

    private Library persistLibrary(String name) {
        Library library = new Library();
        library.setName(name);
        return libraryRepository.save(library);
    }

    private User persistUser(Library library, String username, Role role) {
        User user = new User();
        user.setUsername(username);
        user.setEmail(username + "@example.invalid");
        user.setPassword(encodedPassword);
        user.setRole(role);
        user.setLibrary(library);
        return userRepository.save(user);
    }

    /** Appends one event with an exact time, through JPA like the service does. */
    private Long append(Library library, Long actorUserId, AuditAction action, AuditTargetType targetType,
            Long targetId, AuditOutcome outcome, LocalDateTime occurredAt) {
        return auditEventRepository.save(new AuditEvent(library, actorUserId, action, targetType, targetId, outcome,
                occurredAt)).getId();
    }

    private long eventCount(Library library) {
        return auditEventRepository.findByLibraryId(library.getId(), PageRequest.of(0, 200)).getTotalElements();
    }

    private String token(User user) throws Exception {
        String body = objectMapper.createObjectNode()
                .put("username", user.getUsername())
                .put("password", PASSWORD)
                .toString();
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();

        assertThat(result.getResponse().getStatus()).as("login for %s", user.getUsername()).isEqualTo(200);
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("token").asText();
    }

    private MvcResult perform(MockHttpServletRequestBuilder request, String token) throws Exception {
        if (token != null) {
            request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        return mockMvc.perform(request).andReturn();
    }

    /** GET /api/audit-events with a query string, as the given account. */
    private MvcResult read(String query, User account) throws Exception {
        return perform(get("/api/audit-events" + query), token(account));
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private static int status(MvcResult result) {
        return result.getResponse().getStatus();
    }

    /** The ids on a page, in the order they were returned. */
    private List<Long> ids(MvcResult result) throws Exception {
        List<Long> ids = new ArrayList<>();
        for (JsonNode event : json(result).path("content")) {
            ids.add(event.path("id").asLong());
        }
        return ids;
    }

    // ---------- who may read the log ----------

    @Test
    void anonymousIsRefusedBeforeTheLogIsTouched() throws Exception {
        assertThat(status(perform(get("/api/audit-events"), null))).isEqualTo(401);
        assertThat(status(perform(head("/api/audit-events"), null))).isEqualTo(401);
    }

    @Test
    void librariansAndMembersAreRefusedByTheFilterChain() throws Exception {
        for (User account : List.of(librarianA, memberA)) {
            MvcResult result = read("", account);

            assertThat(status(result)).as(account.getUsername()).isEqualTo(403);
            assertThat(json(result).path("message").asText())
                    .as("the chain refuses, not the service")
                    .isEqualTo(FILTER_REFUSAL);
        }
    }

    @Test
    void anAdministratorReadsTheirOwnLibrarysLog() throws Exception {
        MvcResult result = read("", adminA);

        assertThat(status(result)).isEqualTo(200);
        assertThat(ids(result)).containsExactlyInAnyOrderElementsOf(eventsOfA);
    }

    @Test
    void headIsAllowedAndRefusedExactlyLikeGet() throws Exception {
        assertThat(status(perform(head("/api/audit-events"), token(adminA)))).isEqualTo(200);
        assertThat(status(perform(head("/api/audit-events"), token(librarianA)))).isEqualTo(403);
        assertThat(status(perform(head("/api/audit-events"), token(memberA)))).isEqualTo(403);
    }

    // ---------- one library only ----------

    @Test
    void anotherLibrarysEventsAreNeverOnThePage() throws Exception {
        assertThat(ids(read("?size=50", adminA))).doesNotContain(eventOfB);
        assertThat(ids(read("?size=50", adminB))).containsExactly(eventOfB);
    }

    @Test
    void noFilterReachesPastTheCallersLibrary() throws Exception {
        MvcResult byOtherActor = read("?actorUserId=" + adminB.getId(), adminA);
        assertThat(status(byOtherActor)).isEqualTo(200);
        assertThat(ids(byOtherActor)).as("an actor of another library matches nothing").isEmpty();

        MvcResult byOtherTarget = read("?targetType=USER&targetId=" + memberB.getId(), adminA);
        assertThat(ids(byOtherTarget)).as("a target of another library matches nothing").isEmpty();

        MvcResult wideOpen = read("?size=50&from=" + base.minusDays(1) + "&to=" + base.plusDays(1), adminA);
        assertThat(ids(wideOpen)).as("even an all-inclusive range stays in library A")
                .containsExactlyInAnyOrderElementsOf(eventsOfA);
    }

    // ---------- order and pages ----------

    @Test
    void theNewestEventComesFirstByDefault() throws Exception {
        List<Long> newestFirst = new ArrayList<>(eventsOfA);
        Collections.reverse(newestFirst);

        assertThat(ids(read("", adminA))).containsExactlyElementsOf(newestFirst);
    }

    @Test
    void pagesFollowTheSameRulesAsTheRestOfTheApi() throws Exception {
        MvcResult first = read("?page=0&size=2", adminA);
        JsonNode body = json(first);

        assertThat(body.path("page").asInt()).isEqualTo(0);
        assertThat(body.path("size").asInt()).isEqualTo(2);
        assertThat(body.path("totalElements").asLong()).isEqualTo(eventsOfA.size());
        assertThat(body.path("totalPages").asInt()).isEqualTo(3);

        List<Long> firstPage = ids(first);
        List<Long> secondPage = ids(read("?page=1&size=2", adminA));

        assertThat(firstPage).hasSize(2);
        assertThat(secondPage).hasSize(2).doesNotContainAnyElementsOf(firstPage);
    }

    @Test
    void aPageCannotBeWidenedOrNegative() throws Exception {
        for (String query : List.of("?size=51", "?size=0", "?page=-1")) {
            MvcResult result = read(query, adminA);

            assertThat(status(result)).as(query).isEqualTo(400);
        }

        assertThat(status(read("?size=50", adminA))).as("fifty is still allowed").isEqualTo(200);
    }

    @Test
    void onlyTheTimeAndTheIdCanBeSortedOn() throws Exception {
        assertThat(status(read("?sortBy=actorUserId", adminA))).isEqualTo(400);
        assertThat(status(read("?sortBy=action", adminA))).isEqualTo(400);
        assertThat(status(read("?sortBy=password", adminA))).isEqualTo(400);
        assertThat(status(read("?direction=sideways", adminA))).isEqualTo(400);

        assertThat(ids(read("?sortBy=id&direction=asc", adminA))).containsExactlyElementsOf(eventsOfA);
        assertThat(status(read("?sortBy=occurredAt&direction=asc", adminA))).isEqualTo(200);
    }

    // ---------- filters ----------

    @Test
    void eachFilterNarrowsTheLibrarysLog() throws Exception {
        assertThat(ids(read("?action=USER_CREATED", adminA)))
                .containsExactly(eventsOfA.get(1));

        assertThat(ids(read("?outcome=FAILURE", adminA)))
                .containsExactly(eventsOfA.get(2));

        assertThat(ids(read("?actorUserId=" + librarianA.getId(), adminA)))
                .containsExactlyInAnyOrder(eventsOfA.get(2), eventsOfA.get(3));

        assertThat(ids(read("?targetType=LIBRARY&targetId=" + libraryA.getId(), adminA)))
                .containsExactly(eventsOfA.get(4));

        assertThat(ids(read("?from=" + base.plusMinutes(3), adminA)))
                .containsExactlyInAnyOrder(eventsOfA.get(3), eventsOfA.get(4));

        assertThat(ids(read("?to=" + base.plusMinutes(1), adminA)))
                .containsExactlyInAnyOrder(eventsOfA.get(0), eventsOfA.get(1));
    }

    @Test
    void filtersCombineAndAllOfThemMustMatch() throws Exception {
        String bothMatch = "?actorUserId=" + librarianA.getId() + "&outcome=FAILURE";
        assertThat(ids(read(bothMatch, adminA))).containsExactly(eventsOfA.get(2));

        String oneMatches = "?actorUserId=" + adminA.getId() + "&outcome=FAILURE";
        assertThat(ids(read(oneMatches, adminA))).isEmpty();

        String range = "?from=" + base.plusMinutes(1) + "&to=" + base.plusMinutes(3)
                + "&targetType=USER&targetId=" + memberA.getId();
        assertThat(ids(read(range, adminA))).containsExactlyInAnyOrder(eventsOfA.get(1), eventsOfA.get(3));
    }

    @Test
    void anUnknownFilterValueIsRefusedRatherThanIgnored() throws Exception {
        assertThat(status(read("?action=DELETED_EVERYTHING", adminA))).isEqualTo(400);
        assertThat(status(read("?outcome=MAYBE", adminA))).isEqualTo(400);
        assertThat(status(read("?from=yesterday", adminA))).isEqualTo(400);
    }

    // ---------- what an event shows, and what reading one costs ----------

    @Test
    void anEventCarriesTheSevenFieldsAndNothingElse() throws Exception {
        JsonNode event = json(read("?action=USER_CREATED", adminA)).path("content").get(0);

        List<String> fields = new ArrayList<>();
        event.fieldNames().forEachRemaining(fields::add);

        assertThat(fields).containsExactlyInAnyOrderElementsOf(EVENT_FIELDS);
        assertThat(event.path("action").asText()).isEqualTo("USER_CREATED");
        assertThat(event.path("outcome").asText()).isEqualTo("SUCCESS");
        assertThat(event.path("actorUserId").asLong()).isEqualTo(adminA.getId());
        assertThat(event.path("targetType").asText()).isEqualTo("USER");
        assertThat(event.path("targetId").asLong()).isEqualTo(memberA.getId());
        assertThat(event.path("occurredAt").asText()).startsWith(base.plusMinutes(1).toString());
    }

    @Test
    void readingTheLogIsNotItselfRecorded() throws Exception {
        long before = eventCount(libraryA);

        assertThat(status(read("", adminA))).isEqualTo(200);
        assertThat(status(read("?action=USER_CREATED", adminA))).isEqualTo(200);
        assertThat(status(perform(head("/api/audit-events"), token(adminA)))).isEqualTo(200);
        assertThat(status(read("", memberA))).isEqualTo(403);

        assertThat(eventCount(libraryA))
                .as("reads add nothing to the log, refused ones included")
                .isEqualTo(before);
    }

    @Test
    void aChangeMadeThroughTheApiIsThereToRead() throws Exception {
        String adminToken = token(adminA);
        String username = "audit-new-" + UUID.randomUUID().toString().substring(0, 8);
        String body = objectMapper.createObjectNode()
                .put("username", username)
                .put("email", username + "@example.invalid")
                .put("password", "another-test-only-password")
                .put("role", "ROLE_MEMBER")
                .toString();

        MvcResult created = mockMvc.perform(post("/api/users")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
        assertThat(status(created)).isEqualTo(201);
        long newUserId = json(created).path("id").asLong();

        JsonNode logged = json(read("?action=USER_CREATED&targetType=USER&targetId=" + newUserId, adminA));

        assertThat(logged.path("totalElements").asLong()).isEqualTo(1);
        JsonNode event = logged.path("content").get(0);
        assertThat(event.path("outcome").asText()).isEqualTo("SUCCESS");
        assertThat(event.path("actorUserId").asLong()).isEqualTo(adminA.getId());
        assertThat(event.path("targetId").asLong()).isEqualTo(newUserId);
    }
}
