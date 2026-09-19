package com.library.lms.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.library.lms.entity.Library;
import com.library.lms.entity.Role;
import com.library.lms.entity.User;
import com.library.lms.repository.LibraryRepository;
import com.library.lms.repository.RefreshTokenRepository;
import com.library.lms.repository.UserRepository;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * The staff password reset over HTTP: who may reset whose password, what the
 * reset changes, and what it must never reveal.
 *
 * <p><b>Two libraries per test.</b> Library A has two administrators, two
 * librarians and two members; library B an administrator and a member. Every
 * account starts with the same test password, so "the password did not change"
 * is simply "the old password still signs in".</p>
 *
 * <p><b>Both locks against members are tested apart.</b> The filter chain
 * refuses a member before the controller, with its own fixed sentence - the
 * one with the full stop - and {@code PasswordResetServiceTest} refuses one at
 * the service with no filter chain in front. The librarian-to-staff rule lives
 * only in the service; its 403 carries the service's sentence.</p>
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
class PasswordResetIntegrationTest {

    /** Test-only credentials, never real ones. */
    private static final String OLD_PASSWORD = "reset-test-old-password";

    private static final String NEW_PASSWORD = "reset-test-new-password-7f3a";

    /** The filter chain's refusal; the service's has no full stop. */
    private static final String FILTER_REFUSAL = "Access denied.";

    private static final String SERVICE_REFUSAL = "Access denied";

    /** Hashed once: BCrypt is slow on purpose, and every fixture account starts with the same password. */
    private static String encodedOldPassword;

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
    private PasswordEncoder passwordEncoder;

    private String suffix;

    private User adminA;
    private User secondAdminA;
    private User librarianA;
    private User secondLibrarianA;
    private User memberA;
    private User secondMemberA;
    private User adminB;
    private User memberB;

    private String adminToken;
    private String librarianToken;
    private String memberToken;

    // ---------- fixtures ----------

    @BeforeEach
    void createTwoLibraries() throws Exception {
        if (encodedOldPassword == null) {
            encodedOldPassword = passwordEncoder.encode(OLD_PASSWORD);
        }
        suffix = UUID.randomUUID().toString().substring(0, 8);

        Library libraryA = newLibrary("A");
        adminA = persistUser(libraryA, "a-admin", Role.ROLE_ADMIN);
        secondAdminA = persistUser(libraryA, "a-admin-2", Role.ROLE_ADMIN);
        librarianA = persistUser(libraryA, "a-librarian", Role.ROLE_LIBRARIAN);
        secondLibrarianA = persistUser(libraryA, "a-librarian-2", Role.ROLE_LIBRARIAN);
        memberA = persistUser(libraryA, "a-member", Role.ROLE_MEMBER);
        secondMemberA = persistUser(libraryA, "a-member-2", Role.ROLE_MEMBER);

        Library libraryB = newLibrary("B");
        adminB = persistUser(libraryB, "b-admin", Role.ROLE_ADMIN);
        memberB = persistUser(libraryB, "b-member", Role.ROLE_MEMBER);

        adminToken = session(adminA).path("token").asText();
        librarianToken = session(librarianA).path("token").asText();
        memberToken = session(memberA).path("token").asText();
    }

    private Library newLibrary(String label) {
        Library library = new Library();
        library.setName("Reset Library " + label + " " + suffix);
        return libraryRepository.save(library);
    }

    private User persistUser(Library library, String label, Role role) {
        User user = new User();
        user.setUsername("reset-" + suffix + "-" + label);
        user.setEmail("reset-" + suffix + "-" + label + "@example.invalid");
        user.setPassword(encodedOldPassword);
        user.setRole(role);
        user.setLibrary(library);
        return userRepository.save(user);
    }

    // ---------- helpers ----------

    private MvcResult attemptLogin(User user, String password) throws Exception {
        String body = objectMapper.createObjectNode()
                .put("username", user.getUsername())
                .put("password", password)
                .toString();

        return mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn();
    }

    /** Logs in with the old password and returns the whole response: access and refresh token. */
    private JsonNode session(User user) throws Exception {
        MvcResult result = attemptLogin(user, OLD_PASSWORD);
        assertThat(status(result)).as("login for %s", user.getUsername()).isEqualTo(200);
        return json(result);
    }

    private MvcResult reset(User target, String newPassword, String token) throws Exception {
        return reset(target.getId(), objectMapper.createObjectNode().put("newPassword", newPassword).toString(),
                token);
    }

    private MvcResult reset(Object targetId, String body, String token) throws Exception {
        MockHttpServletRequestBuilder request = post("/api/users/{id}/password-reset", targetId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
        return perform(request, token);
    }

    private MvcResult perform(MockHttpServletRequestBuilder request, String token) throws Exception {
        if (token != null) {
            request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        return mockMvc.perform(request).andReturn();
    }

    private MvcResult refresh(String refreshToken) throws Exception {
        String body = objectMapper.createObjectNode().put("refreshToken", refreshToken).toString();
        return mockMvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn();
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private static int status(MvcResult result) {
        return result.getResponse().getStatus();
    }

    private void assertPasswordUnchanged(User user) throws Exception {
        assertThat(status(attemptLogin(user, OLD_PASSWORD))).as("%s still has the old password", user.getUsername())
                .isEqualTo(200);
        assertThat(status(attemptLogin(user, NEW_PASSWORD))).as("and not the new one").isEqualTo(401);
    }

    private void assertPasswordReset(User user) throws Exception {
        assertThat(status(attemptLogin(user, NEW_PASSWORD))).as("%s signs in with the new password", user.getUsername())
                .isEqualTo(200);
        assertThat(status(attemptLogin(user, OLD_PASSWORD))).as("and no longer with the old one").isEqualTo(401);
    }

    // ---------- who may reset whose ----------

    @Test
    void anAdministratorResetsAMembersPassword() throws Exception {
        MvcResult result = reset(memberA, NEW_PASSWORD, adminToken);

        assertThat(status(result)).isEqualTo(204);
        assertThat(result.getResponse().getContentAsString()).as("no body to leak into").isEmpty();
        assertPasswordReset(memberA);
    }

    @Test
    void anAdministratorResetsAnyStaffAccountOfTheirLibraryButTheirOwn() throws Exception {
        assertThat(status(reset(librarianA, NEW_PASSWORD, adminToken))).as("a librarian").isEqualTo(204);
        assertThat(status(reset(secondAdminA, NEW_PASSWORD, adminToken))).as("another administrator").isEqualTo(204);

        assertPasswordReset(librarianA);
        assertPasswordReset(secondAdminA);
    }

    @Test
    void anAdministratorCannotResetTheirOwnPasswordHere() throws Exception {
        MvcResult refused = reset(adminA, NEW_PASSWORD, adminToken);

        assertThat(status(refused)).isEqualTo(400);
        assertThat(json(refused).path("message").asText()).contains("/api/auth/password");
        assertPasswordUnchanged(adminA);
    }

    @Test
    void aLibrarianResetsAMembersPassword() throws Exception {
        assertThat(status(reset(memberA, NEW_PASSWORD, librarianToken))).isEqualTo(204);
        assertPasswordReset(memberA);
    }

    @Test
    void aLibrarianCannotResetAnyStaffAccountNotEvenTheirOwn() throws Exception {
        for (User staff : List.of(adminA, secondLibrarianA, librarianA)) {
            MvcResult refused = reset(staff, NEW_PASSWORD, librarianToken);

            assertThat(status(refused)).as(staff.getUsername()).isEqualTo(403);
            assertThat(json(refused).path("message").asText())
                    .as("refused by UserService, past the filter chain")
                    .isEqualTo(SERVICE_REFUSAL);
            assertPasswordUnchanged(staff);
        }
    }

    @Test
    void aMemberIsStoppedByTheFilterChainForAnyAccountIncludingTheirOwn() throws Exception {
        for (User target : List.of(secondMemberA, librarianA, memberA)) {
            MvcResult refused = reset(target, NEW_PASSWORD, memberToken);

            assertThat(status(refused)).as(target.getUsername()).isEqualTo(403);
            assertThat(json(refused).path("message").asText())
                    .as("refused in the filter chain, never reaching the controller")
                    .isEqualTo(FILTER_REFUSAL);
            assertPasswordUnchanged(target);
        }
    }

    @Test
    void anAnonymousCallerIsRefusedWith401() throws Exception {
        assertThat(status(reset(memberA, NEW_PASSWORD, null))).isEqualTo(401);
        assertPasswordUnchanged(memberA);
    }

    @Test
    void onlyPostIsOpenedToLibrariansOnThisPath() throws Exception {
        String path = "/api/users/{id}/password-reset";
        for (MockHttpServletRequestBuilder other : List.of(get(path, memberA.getId()), head(path, memberA.getId()),
                put(path, memberA.getId()), delete(path, memberA.getId()))) {
            assertThat(status(perform(other, librarianToken)))
                    .as("every other verb stays administrators only")
                    .isEqualTo(403);
        }
    }

    // ---------- strict library isolation ----------

    @Test
    void anotherLibrarysAccountIsTheSame404AsAMissingOne() throws Exception {
        assertThat(status(reset(memberB, NEW_PASSWORD, adminToken))).as("another library's member").isEqualTo(404);
        assertThat(status(reset(adminB, NEW_PASSWORD, adminToken))).as("another library's administrator")
                .isEqualTo(404);
        assertThat(status(reset(memberB, NEW_PASSWORD, librarianToken))).as("from a librarian").isEqualTo(404);
        assertThat(status(reset(Long.MAX_VALUE, "{\"newPassword\":\"" + NEW_PASSWORD + "\"}", adminToken)))
                .as("an id that exists nowhere")
                .isEqualTo(404);

        assertPasswordUnchanged(memberB);
        assertPasswordUnchanged(adminB);
    }

    // ---------- what the reset changes ----------

    @Test
    void theNewPasswordIsStoredOnlyAsABcryptHash() throws Exception {
        reset(memberA, NEW_PASSWORD, adminToken);

        String stored = userRepository.findById(memberA.getId()).orElseThrow().getPassword();

        assertThat(stored).isNotEqualTo(NEW_PASSWORD).startsWith("$2");
        assertThat(stored).as("a fresh hash, not the old one").isNotEqualTo(encodedOldPassword);
        assertThat(passwordEncoder.matches(NEW_PASSWORD, stored)).isTrue();
    }

    @Test
    void everyRefreshSessionOfTheTargetEndsAndNobodyElses() throws Exception {
        String firstDevice = session(secondMemberA).path("refreshToken").asText();
        String secondDevice = session(secondMemberA).path("refreshToken").asText();
        String bystander = session(memberA).path("refreshToken").asText();
        String resettersOwn = session(adminA).path("refreshToken").asText();

        assertThat(status(reset(secondMemberA, NEW_PASSWORD, adminToken))).isEqualTo(204);

        assertThat(refreshTokenRepository.findByUserIdAndRevokedAtIsNull(secondMemberA.getId()))
                .as("no live refresh token left for the target")
                .isEmpty();
        assertThat(status(refresh(firstDevice))).as("the target's first session").isEqualTo(401);
        assertThat(status(refresh(secondDevice))).as("the target's second session").isEqualTo(401);

        assertThat(status(refresh(bystander))).as("another member's session is untouched").isEqualTo(200);
        assertThat(status(refresh(resettersOwn))).as("the administrator's own session is untouched").isEqualTo(200);
    }

    @Test
    void aRateLimitedAccountCanSignInAtOnceWithTheNewPassword() throws Exception {
        for (int attempt = 0; attempt < 5; attempt++) {
            assertThat(status(attemptLogin(secondMemberA, "a-wrong-guess-" + attempt))).isEqualTo(401);
        }
        assertThat(status(attemptLogin(secondMemberA, OLD_PASSWORD)))
                .as("blocked: even the right password is refused after five failures")
                .isEqualTo(401);

        assertThat(status(reset(secondMemberA, NEW_PASSWORD, librarianToken))).isEqualTo(204);

        assertThat(status(attemptLogin(secondMemberA, NEW_PASSWORD)))
                .as("the reset cleared the block, so the owner signs in straight away")
                .isEqualTo(200);
    }

    @Test
    void theSelfServiceChangeIsUnchanged() throws Exception {
        String body = objectMapper.createObjectNode()
                .put("currentPassword", OLD_PASSWORD)
                .put("newPassword", NEW_PASSWORD)
                .toString();

        MvcResult changed = perform(post("/api/auth/password").contentType(MediaType.APPLICATION_JSON).content(body),
                adminToken);

        assertThat(status(changed)).as("an administrator changes their own password the usual way").isEqualTo(204);
        assertPasswordReset(adminA);
    }

    // ---------- validation ----------

    @Test
    void aPasswordOutsideTheCreationRulesIsRefusedAndChangesNothing() throws Exception {
        for (String invalid : List.of("7-chars", "x".repeat(73), "        ")) {
            MvcResult refused = reset(memberA, invalid, adminToken);

            assertThat(status(refused)).as("%d characters", invalid.length()).isEqualTo(400);
            if (!invalid.isBlank()) {
                assertThat(refused.getResponse().getContentAsString())
                        .as("the rejected value is never echoed")
                        .doesNotContain(invalid);
            }
        }

        assertThat(status(reset(memberA.getId(), "{}", adminToken))).as("missing").isEqualTo(400);
        assertThat(status(reset(memberA.getId(), "{\"newPassword\":", adminToken))).as("malformed").isEqualTo(400);
        assertThat(status(reset("abc", "{\"newPassword\":\"" + NEW_PASSWORD + "\"}", adminToken)))
                .as("a non-numeric id")
                .isEqualTo(400);
        assertThat(status(reset(0, "{\"newPassword\":\"" + NEW_PASSWORD + "\"}", adminToken)))
                .as("ids are positive")
                .isEqualTo(400);

        assertPasswordUnchanged(memberA);
    }

    @Test
    void theBoundaryLengthsAreAccepted() throws Exception {
        assertThat(status(reset(memberA, "8-chars!", adminToken))).as("8 characters").isEqualTo(204);
        assertThat(status(reset(secondMemberA, "y".repeat(72), adminToken))).as("72 characters").isEqualTo(204);
    }

    // ---------- what it says about it ----------

    @Test
    void neitherThePasswordNorItsHashReachesAnyLogLine() throws Exception {
        Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);

        try {
            assertThat(status(reset(memberA, NEW_PASSWORD, adminToken))).isEqualTo(204);
            assertThat(status(reset(adminA, NEW_PASSWORD, adminToken))).isEqualTo(400);
            assertThat(status(reset(adminA, NEW_PASSWORD, librarianToken))).isEqualTo(403);
            assertThat(status(reset(memberA, "7-chars", adminToken))).isEqualTo(400);
        } finally {
            root.detachAppender(appender);
            appender.stop();
        }

        String hash = userRepository.findById(memberA.getId()).orElseThrow().getPassword();
        List<String> lines = appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();

        assertThat(lines).as("the reset is recorded, by id")
                .anyMatch(line -> line.contains("Password reset by admin") && line.contains("id=" + memberA.getId()));
        assertThat(lines).allSatisfy(line -> assertThat(line)
                .doesNotContain(NEW_PASSWORD)
                .doesNotContain("7-chars")
                .doesNotContain(hash)
                .doesNotContain(encodedOldPassword)
                .doesNotContain(memberA.getEmail()));
    }
}
