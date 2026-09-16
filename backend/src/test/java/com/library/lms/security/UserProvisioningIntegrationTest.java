package com.library.lms.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.lang.reflect.Field;
import java.util.Arrays;
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
import com.library.lms.dto.ChangePasswordRequest;
import com.library.lms.dto.CreateUserRequest;
import com.library.lms.entity.Library;
import com.library.lms.entity.Role;
import com.library.lms.entity.User;
import com.library.lms.repository.LibraryRepository;
import com.library.lms.repository.UserRepository;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * Proves an administrator can create the accounts of their own library, and
 * that anyone can change their own password - the two things that previously
 * had no path through the API at all.
 *
 * <p>Before this, accounts existed only if someone inserted a row with an
 * externally generated BCrypt hash, and a password could never be changed: a
 * compromised one could only be disabled.</p>
 *
 * <p><b>Two boundaries carry the weight.</b> The new account's library is read
 * from the administrator, never from the request, so a body carrying a
 * {@code libraryId} is simply ignored - that is asserted, not assumed. And an
 * administrator cannot create another administrator, which would make the one
 * privilege nobody else can grant self-propagating.</p>
 *
 * <p><b>The password is followed all the way to the row.</b> The tests read the
 * stored value back and require it to be a BCrypt hash rather than anything
 * resembling what was typed, and the log is captured to prove neither the
 * created password nor a changed one is written down.</p>
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
class UserProvisioningIntegrationTest {

    /** Test-only credential, never a real one, and never reused outside this class. */
    private static final String TEST_PASSWORD = "step149-test-only-password";

    /** The password new accounts are created with here. */
    private static final String NEW_ACCOUNT_PASSWORD = "step149-new-account-password";

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
    private Library libraryA;
    private Library libraryB;
    private User admin;
    private User librarian;
    private User member;
    private String adminToken;
    private String librarianToken;
    private String memberToken;

    // ---------- fixtures ----------

    @BeforeEach
    void createLibrariesAndStaff() throws Exception {
        suffix = UUID.randomUUID().toString().substring(0, 8);

        libraryA = newLibrary("A");
        libraryB = newLibrary("B");

        admin = persistUser(libraryA, Role.ROLE_ADMIN, "admin");
        librarian = persistUser(libraryA, Role.ROLE_LIBRARIAN, "librarian");
        member = persistUser(libraryA, Role.ROLE_MEMBER, "member");

        adminToken = login(admin.getUsername(), TEST_PASSWORD);
        librarianToken = login(librarian.getUsername(), TEST_PASSWORD);
        memberToken = login(member.getUsername(), TEST_PASSWORD);
    }

    private Library newLibrary(String label) {
        Library library = new Library();
        library.setName("Step149 Library " + label + " " + suffix);
        return libraryRepository.save(library);
    }

    private User persistUser(Library library, Role role, String label) {
        String username = "step149-" + label + "-" + suffix;

        User user = new User();
        user.setUsername(username);
        user.setEmail(username + "@example.invalid");
        user.setPassword(passwordEncoder.encode(TEST_PASSWORD));
        user.setFullName("Step149 " + label);
        user.setRole(role);
        user.setLibrary(library);
        return userRepository.save(user);
    }

    // ---------- helpers ----------

    private MvcResult attemptLogin(String username, String password) throws Exception {
        String body = objectMapper.createObjectNode()
                .put("username", username)
                .put("password", password)
                .toString();

        return mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
    }

    private String login(String username, String password) throws Exception {
        MvcResult result = attemptLogin(username, password);

        assertThat(status(result)).as("login for %s", username).isEqualTo(200);
        return json(result).path("token").asText();
    }

    private String createUserJson(String username, String email, String password, String role) {
        return objectMapper.createObjectNode()
                .put("username", username)
                .put("email", email)
                .put("password", password)
                .put("role", role)
                .toString();
    }

    private MvcResult createUser(String body, String token) throws Exception {
        return mockMvc.perform(post("/api/users")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
    }

    private MvcResult changePassword(String current, String replacement, String token) throws Exception {
        String body = objectMapper.createObjectNode()
                .put("currentPassword", current)
                .put("newPassword", replacement)
                .toString();

        return mockMvc.perform(post("/api/auth/password")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
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

    private String newUsername(String label) {
        return "step149-created-" + label + "-" + suffix;
    }

    private User stored(String username) {
        return userRepository.findByUsername(username).orElseThrow();
    }

    // ---------- creating accounts ----------

    @Test
    void anAdministratorCreatesAMember() throws Exception {
        String username = newUsername("member");

        MvcResult result = createUser(
                createUserJson(username, username + "@example.invalid", NEW_ACCOUNT_PASSWORD, "ROLE_MEMBER"),
                adminToken);

        assertThat(status(result)).isEqualTo(201);
        JsonNode body = json(result);
        assertThat(body.path("username").asText()).isEqualTo(username);
        assertThat(body.path("role").asText()).isEqualTo("ROLE_MEMBER");
        assertThat(body.path("enabled").asBoolean()).as("new accounts are usable").isTrue();
        assertThat(body.path("accountNonLocked").asBoolean()).isTrue();
    }

    @Test
    void anAdministratorCreatesALibrarian() throws Exception {
        String username = newUsername("librarian");

        MvcResult result = createUser(
                createUserJson(username, username + "@example.invalid", NEW_ACCOUNT_PASSWORD, "ROLE_LIBRARIAN"),
                adminToken);

        assertThat(status(result)).isEqualTo(201);
        assertThat(json(result).path("role").asText()).isEqualTo("ROLE_LIBRARIAN");
        assertThat(stored(username).getRole()).isEqualTo(Role.ROLE_LIBRARIAN);
    }

    @Test
    void aCreatedAccountBelongsToTheAdministratorsLibrary() throws Exception {
        String username = newUsername("scoped");

        createUser(createUserJson(username, username + "@example.invalid",
                NEW_ACCOUNT_PASSWORD, "ROLE_MEMBER"), adminToken);

        assertThat(stored(username).getLibrary().getId()).isEqualTo(libraryA.getId());
    }

    @Test
    void aLibraryIdInTheRequestCannotPlaceAnAccountElsewhere() throws Exception {
        // The request object has no field for it, so the value is ignored rather
        // than honoured - and the account still lands in the admin's library.
        assertThat(Arrays.stream(CreateUserRequest.class.getDeclaredFields()).map(Field::getName))
                .as("a tenant id must never be client-supplied")
                .doesNotContain("libraryId", "library");

        String username = newUsername("smuggled");
        String body = objectMapper.createObjectNode()
                .put("username", username)
                .put("email", username + "@example.invalid")
                .put("password", NEW_ACCOUNT_PASSWORD)
                .put("role", "ROLE_MEMBER")
                .put("libraryId", libraryB.getId())
                .toString();

        assertThat(status(createUser(body, adminToken))).isEqualTo(201);
        assertThat(stored(username).getLibrary().getId())
                .as("the smuggled library id changed nothing")
                .isEqualTo(libraryA.getId());
    }

    @Test
    void anAdministratorCannotCreateAnotherAdministrator() throws Exception {
        String username = newUsername("admin");

        MvcResult result = createUser(
                createUserJson(username, username + "@example.invalid", NEW_ACCOUNT_PASSWORD, "ROLE_ADMIN"),
                adminToken);

        assertThat(status(result)).isEqualTo(400);
        assertThat(json(result).path("message").asText())
                .isEqualTo("Only ROLE_MEMBER or ROLE_LIBRARIAN accounts may be created.");
        assertThat(userRepository.findByUsername(username)).as("nothing was created").isEmpty();
    }

    @Test
    void aDuplicateUsernameOrEmailIsRefusedWithoutSayingWhich() throws Exception {
        String username = newUsername("twice");
        String email = username + "@example.invalid";
        assertThat(status(createUser(createUserJson(username, email,
                NEW_ACCOUNT_PASSWORD, "ROLE_MEMBER"), adminToken))).isEqualTo(201);

        MvcResult sameUsername = createUser(createUserJson(username, "other-" + email,
                NEW_ACCOUNT_PASSWORD, "ROLE_MEMBER"), adminToken);
        MvcResult sameEmail = createUser(createUserJson(username + "-b", email,
                NEW_ACCOUNT_PASSWORD, "ROLE_MEMBER"), adminToken);

        assertThat(status(sameUsername)).isEqualTo(400);
        assertThat(status(sameEmail)).isEqualTo(400);
        assertThat(json(sameUsername).path("message").asText())
                .as("naming which field clashed would describe an account in another library")
                .isEqualTo(json(sameEmail).path("message").asText())
                .isEqualTo("An account with that username or email already exists.");
    }

    @Test
    void neitherAMemberNorALibrarianMayCreateAccounts() throws Exception {
        // Also proves the ADMIN rule really covers the bare /api/users path: if
        // it did not, these would fall through to "any authenticated user".
        String username = newUsername("forbidden");
        String body = createUserJson(username, username + "@example.invalid",
                NEW_ACCOUNT_PASSWORD, "ROLE_MEMBER");

        for (String token : List.of(memberToken, librarianToken)) {
            MvcResult refused = createUser(body, token);

            assertThat(status(refused)).isEqualTo(403);
            assertThat(json(refused).path("message").asText()).isEqualTo("Access denied.");
        }

        assertThat(userRepository.findByUsername(username)).isEmpty();
    }

    @Test
    void anInvalidRequestIsRefusedBeforeAnyAccountExists() throws Exception {
        String username = newUsername("invalid");

        assertThat(status(createUser(createUserJson(username, username + "@example.invalid",
                "short", "ROLE_MEMBER"), adminToken))).as("password too short").isEqualTo(400);
        assertThat(status(createUser(createUserJson("  ", username + "@example.invalid",
                NEW_ACCOUNT_PASSWORD, "ROLE_MEMBER"), adminToken))).as("blank username").isEqualTo(400);
        assertThat(status(createUser(createUserJson(username, "not-an-email",
                NEW_ACCOUNT_PASSWORD, "ROLE_MEMBER"), adminToken))).as("malformed email").isEqualTo(400);

        assertThat(userRepository.findByUsername(username)).isEmpty();
    }

    // ---------- the password, all the way to the row ----------

    @Test
    void thePasswordIsStoredOnlyAsAHash() throws Exception {
        String username = newUsername("hashed");

        MvcResult result = createUser(createUserJson(username, username + "@example.invalid",
                NEW_ACCOUNT_PASSWORD, "ROLE_MEMBER"), adminToken);

        String storedPassword = stored(username).getPassword();
        assertThat(storedPassword).as("BCrypt, not the typed value").startsWith("$2");
        assertThat(storedPassword).isNotEqualTo(NEW_ACCOUNT_PASSWORD);
        assertThat(passwordEncoder.matches(NEW_ACCOUNT_PASSWORD, storedPassword))
                .as("and it is a hash of what was typed")
                .isTrue();
        assertThat(result.getResponse().getContentAsString())
                .as("nothing resembling a credential comes back")
                .doesNotContain(NEW_ACCOUNT_PASSWORD)
                .doesNotContain(storedPassword);
    }

    @Test
    void aCreatedAccountCanLogInAndUseTheApi() throws Exception {
        String username = newUsername("logs-in");
        createUser(createUserJson(username, username + "@example.invalid",
                NEW_ACCOUNT_PASSWORD, "ROLE_MEMBER"), adminToken);

        String token = login(username, NEW_ACCOUNT_PASSWORD);

        assertThat(mockMvc.perform(get("/api/books").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andReturn().getResponse().getStatus())
                .isEqualTo(200);
    }

    // ---------- changing your own password ----------

    @Test
    void aPasswordChangeNamesNoAccountButTheCallersOwn() {
        assertThat(Arrays.stream(ChangePasswordRequest.class.getDeclaredFields()).map(Field::getName))
                .as("naming a user here would let one account overwrite another's credentials")
                .containsExactlyInAnyOrder("currentPassword", "newPassword");
    }

    @Test
    void theOldPasswordStopsWorkingAndTheNewOneWorks() throws Exception {
        String replacement = "step149-replacement-password";

        assertThat(status(changePassword(TEST_PASSWORD, replacement, memberToken))).isEqualTo(204);

        assertThat(status(attemptLogin(member.getUsername(), TEST_PASSWORD)))
                .as("the old password is gone").isEqualTo(401);
        assertThat(status(attemptLogin(member.getUsername(), replacement)))
                .as("the new one works").isEqualTo(200);
    }

    @Test
    void aWrongCurrentPasswordIsRefusedAndChangesNothing() throws Exception {
        MvcResult refused = changePassword("step149-not-the-password", "step149-replacement", memberToken);

        assertThat(status(refused)).isEqualTo(400);
        assertThat(json(refused).path("message").asText()).isEqualTo("Current password is incorrect.");
        assertThat(status(attemptLogin(member.getUsername(), TEST_PASSWORD)))
                .as("the real password still works")
                .isEqualTo(200);
    }

    @Test
    void everyAuthenticatedRoleMayChangeItsOwnPassword() throws Exception {
        // The endpoint is deliberately not under /api/users, which is admins
        // only - a member who could never change their own password would be a
        // poor answer to a compromised one.
        assertThat(status(changePassword(TEST_PASSWORD, "step149-member-new-password", memberToken))).isEqualTo(204);
        assertThat(status(changePassword(TEST_PASSWORD, "step149-staff-new-password", librarianToken))).isEqualTo(204);
        assertThat(status(changePassword(TEST_PASSWORD, "step149-admin-new-password", adminToken))).isEqualTo(204);
    }

    @Test
    void aPasswordChangeLeavesTheRoleAndLibraryAlone() throws Exception {
        Role roleBefore = stored(member.getUsername()).getRole();
        Long libraryBefore = stored(member.getUsername()).getLibrary().getId();

        assertThat(status(changePassword(TEST_PASSWORD, "step149-still-a-member", memberToken))).isEqualTo(204);

        User after = stored(member.getUsername());
        assertThat(after.getRole()).isEqualTo(roleBefore);
        assertThat(after.getLibrary().getId()).isEqualTo(libraryBefore);
        assertThat(after.isEnabled()).isTrue();
        assertThat(after.isAccountNonLocked()).isTrue();
    }

    @Test
    void aChangedPasswordIsStoredOnlyAsAHash() throws Exception {
        String replacement = "step149-hash-me-too";

        changePassword(TEST_PASSWORD, replacement, memberToken);

        String storedPassword = stored(member.getUsername()).getPassword();
        assertThat(storedPassword).startsWith("$2").isNotEqualTo(replacement);
        assertThat(passwordEncoder.matches(replacement, storedPassword)).isTrue();
    }

    @Test
    void anAlreadyIssuedTokenKeepsWorkingUntilItExpires() throws Exception {
        // Recorded rather than fixed: tokens carry no password, so changing one
        // does not invalidate a session already issued. It lasts at most the
        // token's hour. Revoking on password change would need server-side
        // token state, which this step deliberately does not add.
        changePassword(TEST_PASSWORD, "step149-token-still-valid", memberToken);

        assertThat(mockMvc.perform(get("/api/books").header(HttpHeaders.AUTHORIZATION, "Bearer " + memberToken))
                .andReturn().getResponse().getStatus())
                .isEqualTo(200);
    }

    // ---------- nothing sensitive is written down ----------

    @Test
    void noPasswordFromEitherEndpointEverReachesTheLog() throws Exception {
        String username = newUsername("quiet");
        String replacement = "step149-never-logged-replacement";

        Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);

        try {
            createUser(createUserJson(username, username + "@example.invalid",
                    NEW_ACCOUNT_PASSWORD, "ROLE_MEMBER"), adminToken);
            changePassword(TEST_PASSWORD, replacement, memberToken);
            changePassword("step149-wrong-current", replacement, memberToken);
        } finally {
            root.detachAppender(appender);
            appender.stop();
        }

        List<String> lines = appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();

        assertThat(lines)
                .as("no password, typed or replacement, in any log line")
                .noneMatch(line -> line.contains(NEW_ACCOUNT_PASSWORD)
                        || line.contains(replacement)
                        || line.contains(TEST_PASSWORD));
        assertThat(lines)
                .as("nor the stored hash")
                .noneMatch(line -> line.contains(stored(username).getPassword()));
        assertThat(lines)
                .as("the events themselves are recorded")
                .anyMatch(line -> line.contains("Account created by admin="))
                .anyMatch(line -> line.contains("Password changed for username="));
    }
}
