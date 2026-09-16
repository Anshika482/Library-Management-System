package com.library.lms.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.library.lms.entity.Library;
import com.library.lms.entity.Role;
import com.library.lms.entity.User;
import com.library.lms.repository.LibraryRepository;
import com.library.lms.repository.UserRepository;

/**
 * Proves that switching an account off actually stops it being used - at the
 * login endpoint and, just as importantly, for a token it was already holding.
 *
 * <p><b>The token case is the one worth having.</b> Refusing a disabled account
 * at login is easy and would have looked like success on its own, while every
 * token already issued kept working until it expired - up to an hour of access
 * for an account that had just been retired. The filter reloads the account on
 * every request, so these tests disable a user <i>after</i> they have a token
 * and then reuse that same token.</p>
 *
 * <p><b>Nothing about the status is disclosed.</b> A disabled login, a locked
 * login, a wrong password and an unknown username are compared against each
 * other and must be identical; a rejected token is compared against an ordinary
 * unauthenticated request. An administrator reaching into another library is
 * told exactly what they would be told about an id that does not exist.</p>
 *
 * <p><b>Isolation:</b> the schema the other integration tests use, never the
 * development database. Every test builds two fresh libraries with unique
 * names, so one test's disabled user cannot affect another's.</p>
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:mysql://localhost:3306/library_db_step129_it"
                + "?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true"
                + "&serverTimezone=UTC",
        "spring.jpa.hibernate.ddl-auto=update",
        "spring.jpa.open-in-view=false"
})
@AutoConfigureMockMvc
class AccountStatusIntegrationTest {

    /** Test-only credential, never a real one, and never reused outside this class. */
    private static final String TEST_PASSWORD = "step145-test-only-password";

    private static final String DISABLE = "{\"enabled\":false}";

    private static final String ENABLE = "{\"enabled\":true}";

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
    private User librarian;
    private User member;
    private User otherLibraryMember;
    private String adminToken;
    private String librarianToken;

    // ---------- fixtures ----------

    @BeforeEach
    void createUsers() throws Exception {
        suffix = UUID.randomUUID().toString().substring(0, 8);

        Library library = newLibrary("A");
        admin = persistUser(library, Role.ROLE_ADMIN, "admin");
        librarian = persistUser(library, Role.ROLE_LIBRARIAN, "librarian");
        member = persistUser(library, Role.ROLE_MEMBER, "member");

        otherLibraryMember = persistUser(newLibrary("B"), Role.ROLE_MEMBER, "other");

        adminToken = loginSuccessfully(admin);
        librarianToken = loginSuccessfully(librarian);
    }

    private Library newLibrary(String label) {
        Library library = new Library();
        library.setName("Step145 Library " + label + " " + suffix);
        return libraryRepository.save(library);
    }

    private User persistUser(Library library, Role role, String label) {
        String username = "step145-" + label + "-" + suffix;

        User user = new User();
        user.setUsername(username);
        user.setEmail(username + "@example.invalid");
        user.setPassword(passwordEncoder.encode(TEST_PASSWORD));
        user.setFullName("Step145 " + label);
        user.setRole(role);
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

    private String loginSuccessfully(User user) throws Exception {
        MvcResult result = login(user.getUsername(), TEST_PASSWORD);

        assertThat(status(result)).as("login for %s", user.getUsername()).isEqualTo(200);
        return json(result).path("token").asText();
    }

    private MvcResult call(MockHttpServletRequestBuilder request, String token) throws Exception {
        return mockMvc.perform(request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)).andReturn();
    }

    private MvcResult setStatus(Long userId, String body, String token) throws Exception {
        return call(patch("/api/users/{id}/status", userId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body), token);
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private static int status(MvcResult result) {
        return result.getResponse().getStatus();
    }

    /** Status and message together - all a caller can tell apart. */
    private String signature(MvcResult result) throws Exception {
        return status(result) + " " + json(result).path("message").asText();
    }

    private User reload(User user) {
        return userRepository.findById(user.getId()).orElseThrow();
    }

    // ---------- a normal account ----------

    @Test
    void anEnabledUnlockedAccountLogsInAndItsTokenWorks() throws Exception {
        String token = loginSuccessfully(member);

        assertThat(status(call(get("/api/books"), token))).isEqualTo(200);
        assertThat(reload(member).isEnabled()).isTrue();
        assertThat(reload(member).isAccountNonLocked()).isTrue();
    }

    // ---------- login is refused ----------

    @Test
    void aDisabledAccountCannotLogIn() throws Exception {
        assertThat(status(setStatus(member.getId(), DISABLE, adminToken))).isEqualTo(200);

        assertThat(signature(login(member.getUsername(), TEST_PASSWORD)))
                .isEqualTo("401 Invalid username or password");
    }

    @Test
    void aLockedAccountCannotLogIn() throws Exception {
        assertThat(status(setStatus(member.getId(), LOCK, adminToken))).isEqualTo(200);

        assertThat(signature(login(member.getUsername(), TEST_PASSWORD)))
                .isEqualTo("401 Invalid username or password");
    }

    @Test
    void aRefusedLoginNeverSaysWhy() throws Exception {
        setStatus(member.getId(), DISABLE, adminToken);
        setStatus(librarian.getId(), LOCK, adminToken);

        String disabled = signature(login(member.getUsername(), TEST_PASSWORD));
        String locked = signature(login(librarian.getUsername(), TEST_PASSWORD));
        String wrongPassword = signature(login(admin.getUsername(), "step145-wrong-" + suffix));
        String unknown = signature(login("step145-nobody-" + suffix, TEST_PASSWORD));

        assertThat(disabled).isEqualTo(locked).isEqualTo(wrongPassword).isEqualTo(unknown);
        // "status" is not in this list: it is a field name in every error
        // envelope this API sends, so looking for it would only ever find the
        // wrapper. What must not appear is the reason.
        assertThat(login(member.getUsername(), TEST_PASSWORD).getResponse().getContentAsString().toLowerCase())
                .as("the reply says the login failed, never why")
                .doesNotContain("disabled")
                .doesNotContain("locked")
                .doesNotContain("enabled")
                .doesNotContain("account");
    }

    // ---------- a token already issued stops working ----------

    @Test
    void disablingAnAccountRevokesTheTokenItAlreadyHolds() throws Exception {
        String token = loginSuccessfully(member);
        assertThat(status(call(get("/api/books"), token))).as("works before").isEqualTo(200);

        setStatus(member.getId(), DISABLE, adminToken);

        MvcResult afterwards = call(get("/api/books"), token);
        assertThat(status(afterwards)).as("the same token, now refused").isEqualTo(401);
        assertThat(signature(afterwards))
                .as("indistinguishable from any other unauthenticated request")
                .isEqualTo("401 Authentication required");
    }

    @Test
    void lockingAnAccountRevokesTheTokenItAlreadyHolds() throws Exception {
        String token = loginSuccessfully(member);

        setStatus(member.getId(), LOCK, adminToken);

        assertThat(signature(call(get("/api/books"), token))).isEqualTo("401 Authentication required");
    }

    @Test
    void re_enablingRestoresBothLoginAndAccess() throws Exception {
        setStatus(member.getId(), DISABLE, adminToken);
        assertThat(status(login(member.getUsername(), TEST_PASSWORD))).isEqualTo(401);

        assertThat(status(setStatus(member.getId(), ENABLE, adminToken))).isEqualTo(200);

        String token = loginSuccessfully(member);
        assertThat(status(call(get("/api/books"), token))).isEqualTo(200);
    }

    // ---------- who may change a status ----------

    @Test
    void anAdministratorChangesAccountsInTheirOwnLibrary() throws Exception {
        MvcResult result = setStatus(member.getId(), DISABLE, adminToken);

        assertThat(status(result)).isEqualTo(200);
        JsonNode body = json(result);
        assertThat(body.path("id").asLong()).isEqualTo(member.getId());
        assertThat(body.path("username").asText()).isEqualTo(member.getUsername());
        assertThat(body.path("enabled").asBoolean()).isFalse();
        assertThat(body.path("accountNonLocked").asBoolean()).isTrue();
        assertThat(reload(member).isEnabled()).as("and the row really changed").isFalse();
    }

    @Test
    void onlyTheEnabledFlagMovesWhenOnlyTheEnabledFlagIsSent() throws Exception {
        setStatus(member.getId(), LOCK, adminToken);
        setStatus(member.getId(), DISABLE, adminToken);

        User stored = reload(member);
        assertThat(stored.isEnabled()).isFalse();
        assertThat(stored.isAccountNonLocked()).as("a field left out keeps its value").isFalse();
    }

    @Test
    void neitherAMemberNorALibrarianMayChangeAStatus() throws Exception {
        String memberToken = loginSuccessfully(member);

        for (String token : new String[] {memberToken, librarianToken}) {
            MvcResult refused = setStatus(member.getId(), DISABLE, token);

            assertThat(status(refused)).isEqualTo(403);
            assertThat(json(refused).path("message").asText()).isEqualTo("Access denied.");
        }

        assertThat(reload(member).isEnabled()).as("nothing changed").isTrue();
    }

    @Test
    void anAdministratorCannotReachIntoAnotherLibrary() throws Exception {
        MvcResult refused = setStatus(otherLibraryMember.getId(), DISABLE, adminToken);

        assertThat(status(refused)).isEqualTo(404);
        assertThat(json(refused).path("message").asText())
                .as("exactly what a missing id is told")
                .isEqualTo("User not found with id: " + otherLibraryMember.getId());
        assertThat(reload(otherLibraryMember).isEnabled()).as("their account is untouched").isTrue();
    }

    @Test
    void anIdThatBelongsToNobodyLooksTheSameAsOneFromAnotherLibrary() throws Exception {
        long missingId = 999_999_999L;

        String otherLibrary = json(setStatus(otherLibraryMember.getId(), DISABLE, adminToken))
                .path("message").asText().replace(String.valueOf(otherLibraryMember.getId()), "<id>");
        String nobody = json(setStatus(missingId, DISABLE, adminToken))
                .path("message").asText().replace(String.valueOf(missingId), "<id>");

        assertThat(otherLibrary).isEqualTo(nobody);
    }

    // ---------- everyone else carries on ----------

    @Test
    void disablingOneAccountLeavesEveryOtherWorking() throws Exception {
        String memberToken = loginSuccessfully(member);
        setStatus(member.getId(), DISABLE, adminToken);

        assertThat(status(call(get("/api/books"), memberToken))).as("the disabled one").isEqualTo(401);
        assertThat(status(call(get("/api/books"), librarianToken))).as("a colleague").isEqualTo(200);
        assertThat(status(call(get("/api/books"), adminToken))).as("the administrator").isEqualTo(200);
        assertThat(status(login(librarian.getUsername(), TEST_PASSWORD))).isEqualTo(200);
    }
}
