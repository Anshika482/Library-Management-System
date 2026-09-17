package com.library.lms.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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

/**
 * Proves an administrator cannot disable or lock their own account, and that
 * nothing else about the status endpoint changed.
 *
 * <p><b>Why it matters.</b> The status endpoint is the only way back from a
 * disabled or locked account, and it needs an administrator who can still log
 * in. One who shuts themselves out - and in a library with a single
 * administrator, that is every administrator it has - could not be restored
 * through the API at all.</p>
 *
 * <p><b>A refusal changes nothing.</b> Every refused request is followed by
 * reading the account back: still enabled, still unlocked, still able to log in
 * and to use the token it already holds - including when the request also asked
 * for a change that would on its own have been allowed.</p>
 *
 * <p><b>Everything else stays as it was.</b> The administrator still disables
 * and locks other accounts in their library, another administrator's included;
 * accounts in another library are still not found; and members, librarians and
 * anonymous callers are still refused by the filter chain before the rule is
 * reached.</p>
 *
 * <p><b>Isolation:</b> the throwaway schema the other integration tests use,
 * never the development database, with a unique suffix per test.</p>
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:mysql://localhost:3306/library_db_step129_it"
                + "?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true"
                + "&serverTimezone=UTC",
        "spring.jpa.hibernate.ddl-auto=update",
        "spring.jpa.open-in-view=false"
})
@AutoConfigureMockMvc
class AdminSelfLockoutIntegrationTest {

    /** Test-only credential, never a real one, and never reused outside this class. */
    private static final String TEST_PASSWORD = "step158-test-only-password";

    private static final String SELF_LOCKOUT = "You cannot disable or lock your own account.";

    private static final String DISABLE = "{\"enabled\":false}";

    private static final String LOCK = "{\"accountNonLocked\":false}";

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
    private User admin;
    private User secondAdmin;
    private User librarian;
    private User member;
    private User otherLibraryAdmin;
    private User otherLibraryMember;
    private String adminToken;
    private String secondAdminToken;
    private String librarianToken;
    private String memberToken;
    private String otherLibraryAdminToken;

    // ---------- fixtures ----------

    @BeforeEach
    void createTwoLibraries() throws Exception {
        suffix = UUID.randomUUID().toString().substring(0, 8);

        Library library = newLibrary("A");
        admin = persistUser(library, Role.ROLE_ADMIN, "admin");
        secondAdmin = persistUser(library, Role.ROLE_ADMIN, "second-admin");
        librarian = persistUser(library, Role.ROLE_LIBRARIAN, "librarian");
        member = persistUser(library, Role.ROLE_MEMBER, "member");

        Library otherLibrary = newLibrary("B");
        otherLibraryAdmin = persistUser(otherLibrary, Role.ROLE_ADMIN, "other-admin");
        otherLibraryMember = persistUser(otherLibrary, Role.ROLE_MEMBER, "other-member");

        adminToken = login(admin);
        secondAdminToken = login(secondAdmin);
        librarianToken = login(librarian);
        memberToken = login(member);
        otherLibraryAdminToken = login(otherLibraryAdmin);
    }

    private Library newLibrary(String label) {
        Library library = new Library();
        library.setName("Step158 Library " + label + " " + suffix);
        return libraryRepository.save(library);
    }

    private User persistUser(Library library, Role role, String label) {
        String username = "step158-" + label + "-" + suffix;

        User user = new User();
        user.setUsername(username);
        user.setEmail(username + "@example.invalid");
        user.setPassword(passwordEncoder.encode(TEST_PASSWORD));
        user.setRole(role);
        user.setLibrary(library);
        return userRepository.save(user);
    }

    // ---------- helpers ----------

    private MvcResult attemptLogin(User user) throws Exception {
        String body = objectMapper.createObjectNode()
                .put("username", user.getUsername())
                .put("password", TEST_PASSWORD)
                .toString();

        return mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn();
    }

    private String login(User user) throws Exception {
        MvcResult result = attemptLogin(user);
        assertThat(status(result)).as("login for %s", user.getUsername()).isEqualTo(200);
        return json(result).path("token").asText();
    }

    private MvcResult setStatus(User target, String body, String token) throws Exception {
        return mockMvc.perform(patch("/api/users/{id}/status", target.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
    }

    /** Whether a token still gets through to an ordinary authenticated endpoint. */
    private int booksWith(String token) throws Exception {
        return status(mockMvc.perform(get("/api/books").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andReturn());
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private static int status(MvcResult result) {
        return result.getResponse().getStatus();
    }

    private User stored(User user) {
        return userRepository.findById(user.getId()).orElseThrow();
    }

    /** The self-lockout refusal, in exactly the error shape every other failure uses. */
    private void assertSelfLockoutRefused(String label, MvcResult result) throws Exception {
        assertThat(status(result)).as(label).isEqualTo(400);

        JsonNode body = json(result);
        List<String> fields = new ArrayList<>();
        body.fieldNames().forEachRemaining(fields::add);

        assertThat(fields).as(label).containsExactlyInAnyOrder("status", "message", "timestamp");
        assertThat(body.path("status").asInt()).as(label).isEqualTo(400);
        assertThat(body.path("message").asText()).as(label).isEqualTo(SELF_LOCKOUT);
    }

    /** Still enabled, still unlocked, and still able to log in and use its token. */
    private void assertStillUsable(User user, String token) throws Exception {
        User row = stored(user);
        assertThat(row.isEnabled()).as("%s still enabled", user.getUsername()).isTrue();
        assertThat(row.isAccountNonLocked()).as("%s still unlocked", user.getUsername()).isTrue();
        assertThat(booksWith(token)).as("%s's token still works", user.getUsername()).isEqualTo(200);
        assertThat(status(attemptLogin(user))).as("%s can still log in", user.getUsername()).isEqualTo(200);
    }

    // ---------- the rule ----------

    @Test
    void anAdministratorCannotDisableTheirOwnAccount() throws Exception {
        assertSelfLockoutRefused("self-disable", setStatus(admin, DISABLE, adminToken));

        assertStillUsable(admin, adminToken);
    }

    @Test
    void anAdministratorCannotLockTheirOwnAccount() throws Exception {
        assertSelfLockoutRefused("self-lock", setStatus(admin, LOCK, adminToken));

        assertStillUsable(admin, adminToken);
    }

    @Test
    void aRefusedRequestChangesNeitherSwitch() throws Exception {
        for (String body : List.of(
                "{\"enabled\":false,\"accountNonLocked\":false}",
                "{\"enabled\":true,\"accountNonLocked\":false}",
                "{\"enabled\":false,\"accountNonLocked\":true}")) {
            assertSelfLockoutRefused(body, setStatus(admin, body, adminToken));
        }

        assertStillUsable(admin, adminToken);
    }

    @Test
    void confirmingTheirOwnAccountIsUsableIsStillAllowed() throws Exception {
        for (String body : List.of("{\"enabled\":true,\"accountNonLocked\":true}", "{\"enabled\":true}", "{}")) {
            MvcResult result = setStatus(admin, body, adminToken);

            assertThat(status(result)).as(body).isEqualTo(200);
            assertThat(json(result).path("enabled").asBoolean()).as(body).isTrue();
            assertThat(json(result).path("accountNonLocked").asBoolean()).as(body).isTrue();
        }

        assertStillUsable(admin, adminToken);
    }

    @Test
    void theRuleHoldsInEveryLibrary() throws Exception {
        assertSelfLockoutRefused("other library's admin, self-disable",
                setStatus(otherLibraryAdmin, DISABLE, otherLibraryAdminToken));

        assertStillUsable(otherLibraryAdmin, otherLibraryAdminToken);
    }

    // ---------- everything else is unchanged ----------

    @Test
    void anAdministratorStillManagesEveryoneElseInTheirLibrary() throws Exception {
        assertThat(status(setStatus(librarian, DISABLE, adminToken))).isEqualTo(200);
        assertThat(status(setStatus(member, LOCK, adminToken))).isEqualTo(200);

        assertThat(stored(librarian).isEnabled()).isFalse();
        assertThat(stored(member).isAccountNonLocked()).isFalse();
        assertThat(booksWith(librarianToken)).as("disabled librarian's token").isEqualTo(401);
        assertThat(booksWith(memberToken)).as("locked member's token").isEqualTo(401);
    }

    @Test
    void oneAdministratorMayStillDisableAndRestoreAnother() throws Exception {
        // The recovery path the rule protects: an administrator who can still log
        // in manages every other account, another administrator's included.
        assertThat(status(setStatus(secondAdmin, DISABLE, adminToken))).isEqualTo(200);
        assertThat(stored(secondAdmin).isEnabled()).isFalse();
        assertThat(booksWith(secondAdminToken)).isEqualTo(401);

        assertThat(status(setStatus(secondAdmin, "{\"enabled\":true}", adminToken))).isEqualTo(200);
        assertStillUsable(secondAdmin, login(secondAdmin));
    }

    @Test
    void anotherLibrarysAccountsAreStillNotFound() throws Exception {
        for (User foreign : List.of(otherLibraryMember, otherLibraryAdmin)) {
            MvcResult result = setStatus(foreign, DISABLE, adminToken);

            assertThat(status(result)).as("%s from library A", foreign.getUsername()).isEqualTo(404);
            assertThat(stored(foreign).isEnabled()).isTrue();
        }
    }

    @Test
    void whoMayChangeAStatusIsUnchanged() throws Exception {
        // Refused by the filter chain before the service - and so before the
        // self-lockout rule - is reached, exactly as before.
        assertThat(status(setStatus(librarian, DISABLE, librarianToken))).as("librarian, own account").isEqualTo(403);
        assertThat(status(setStatus(member, LOCK, memberToken))).as("member, own account").isEqualTo(403);

        MvcResult anonymous = mockMvc.perform(patch("/api/users/{id}/status", admin.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(DISABLE))
                .andReturn();
        assertThat(status(anonymous)).isEqualTo(401);

        assertStillUsable(librarian, librarianToken);
        assertStillUsable(member, memberToken);
        assertStillUsable(admin, adminToken);
    }
}
