package com.library.lms.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.library.lms.dto.CreateLibraryRequest;
import com.library.lms.dto.CreateUserRequest;
import com.library.lms.dto.FirstAdminRequest;
import com.library.lms.entity.Book;
import com.library.lms.entity.Library;
import com.library.lms.entity.Role;
import com.library.lms.entity.User;
import com.library.lms.repository.BookRepository;
import com.library.lms.repository.LibraryRepository;
import com.library.lms.repository.UserRepository;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * Proves a new library arrives with its first administrator - created together,
 * confined to that library, and never more than an administrator of it.
 *
 * <p><b>Atomic, and shown to be.</b> The service writes the library row before
 * it checks the administrator's username and email, so a taken one is refused
 * after the library exists and only a rollback can remove it. The tests do not
 * just look for the library's absence, which a check made before any write
 * would also produce: InnoDB never hands an auto-increment value back, so a
 * library inserted and then rolled back leaves a gap in the ids around it, and
 * the gap is measured.</p>
 *
 * <p><b>Confined to the new library.</b> The administrator logs in and works:
 * they see none of the creator's books, create members only in their own
 * library, and cannot reach the creator's accounts - nor the creator theirs.</p>
 *
 * <p><b>No role or library from the client.</b> Both are fixed by the server,
 * whatever the body says, and the request classes have no fields to carry
 * them.</p>
 *
 * <p><b>Existing rules, not new ones.</b> The administrator's fields carry
 * exactly the constraints the user endpoint uses, compared annotation by
 * annotation, and the password is followed into the row and out of the log.</p>
 *
 * <p><b>Isolation:</b> the throwaway schema the other integration tests use,
 * never the development database, with a unique suffix on every name.</p>
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:mysql://localhost:3306/library_db_step129_it"
                + "?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true"
                + "&serverTimezone=UTC",
        "spring.jpa.hibernate.ddl-auto=update",
        "spring.jpa.open-in-view=false"
})
@AutoConfigureMockMvc
class FirstLibraryAdminIntegrationTest {

    /** Test-only credential for the fixture accounts, never a real one. */
    private static final String TEST_PASSWORD = "step152-test-only-password";

    /** Test-only password for the first administrators created here. */
    private static final String FIRST_ADMIN_PASSWORD = "step152-first-admin-password";

    private static final String DUPLICATE_ACCOUNT = "An account with that username or email already exists.";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private LibraryRepository libraryRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private BookRepository bookRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private String suffix;
    private Library ownLibrary;
    private User creator;
    private User ownMember;
    private String creatorToken;

    // ---------- fixtures ----------

    @BeforeEach
    void createTheCreatorsLibrary() throws Exception {
        suffix = UUID.randomUUID().toString().substring(0, 8);

        Library library = new Library();
        library.setName("Step152 Own Library " + suffix);
        ownLibrary = libraryRepository.save(library);

        creator = persistUser(Role.ROLE_ADMIN, "creator");
        ownMember = persistUser(Role.ROLE_MEMBER, "member");

        creatorToken = login(creator.getUsername(), TEST_PASSWORD);
    }

    private User persistUser(Role role, String label) {
        String username = "step152-" + label + "-" + suffix;

        User user = new User();
        user.setUsername(username);
        user.setEmail(username + "@example.invalid");
        user.setPassword(passwordEncoder.encode(TEST_PASSWORD));
        user.setFullName("Step152 " + label);
        user.setRole(role);
        user.setLibrary(ownLibrary);
        return userRepository.save(user);
    }

    // ---------- helpers ----------

    private String login(String username, String password) throws Exception {
        String body = objectMapper.createObjectNode()
                .put("username", username)
                .put("password", password)
                .toString();

        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();

        assertThat(status(result)).as("login for %s", username).isEqualTo(200);
        return json(result).path("token").asText();
    }

    private ObjectNode libraryBody(String name, String adminUsername, String adminEmail, String adminPassword) {
        ObjectNode body = objectMapper.createObjectNode().put("name", name);
        body.putObject("admin")
                .put("username", adminUsername)
                .put("email", adminEmail)
                .put("password", adminPassword);
        return body;
    }

    private MvcResult createLibrary(String body) throws Exception {
        return call(post("/api/libraries").contentType(MediaType.APPLICATION_JSON).content(body), creatorToken);
    }

    private MvcResult call(MockHttpServletRequestBuilder request, String token) throws Exception {
        return mockMvc.perform(request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)).andReturn();
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private static int status(MvcResult result) {
        return result.getResponse().getStatus();
    }

    private String adminUsername(String label) {
        return "step152-first-" + label + "-" + suffix;
    }

    private String libraryName(String label) {
        return "Step152 " + label + " " + suffix;
    }

    private void assertError(MvcResult result, int expectedStatus, String expectedMessage) throws Exception {
        assertThat(status(result)).isEqualTo(expectedStatus);
        assertThat(json(result).path("status").asInt()).isEqualTo(expectedStatus);
        assertThat(json(result).path("message").asText()).isEqualTo(expectedMessage);
    }

    // ---------- created together ----------

    @Test
    void theLibraryAndItsFirstAdministratorAreCreatedTogether() throws Exception {
        String username = adminUsername("together");

        MvcResult result = createLibrary(libraryBody(libraryName("Together"),
                "  " + username + " ", username + "@example.invalid", FIRST_ADMIN_PASSWORD).toString());

        assertThat(status(result)).isEqualTo(201);
        JsonNode admin = json(result).path("admin");

        List<String> fields = new ArrayList<>();
        admin.fieldNames().forEachRemaining(fields::add);
        assertThat(fields)
                .as("an account description with no password and no hash")
                .containsExactlyInAnyOrder("id", "username", "email", "role", "enabled", "accountNonLocked");
        assertThat(admin.path("username").asText()).as("stored trimmed").isEqualTo(username);
        assertThat(admin.path("role").asText()).isEqualTo("ROLE_ADMIN");
        assertThat(admin.path("enabled").asBoolean()).isTrue();
        assertThat(admin.path("accountNonLocked").asBoolean()).isTrue();

        User stored = userRepository.findByUsername(username).orElseThrow();
        assertThat(stored.getId()).isEqualTo(admin.path("id").asLong());
        assertThat(stored.getRole()).isEqualTo(Role.ROLE_ADMIN);
        assertThat(stored.getLibrary().getId())
                .as("the administrator belongs to the library created with them")
                .isEqualTo(json(result).path("id").asLong());
    }

    @Test
    void thePasswordIsStoredOnlyAsABcryptHashAndNeverLogged() throws Exception {
        String username = adminUsername("hashed");

        Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);

        MvcResult result;
        try {
            result = createLibrary(libraryBody(libraryName("Hashed"),
                    username, username + "@example.invalid", FIRST_ADMIN_PASSWORD).toString());
        } finally {
            root.detachAppender(appender);
            appender.stop();
        }

        assertThat(status(result)).isEqualTo(201);

        String hash = userRepository.findByUsername(username).orElseThrow().getPassword();
        assertThat(hash).startsWith("$2").isNotEqualTo(FIRST_ADMIN_PASSWORD);
        assertThat(passwordEncoder.matches(FIRST_ADMIN_PASSWORD, hash)).isTrue();

        assertThat(result.getResponse().getContentAsString())
                .doesNotContain(FIRST_ADMIN_PASSWORD)
                .doesNotContain(hash);

        List<String> lines = appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
        assertThat(lines).noneMatch(line -> line.contains(FIRST_ADMIN_PASSWORD) || line.contains(hash));
        assertThat(lines).anyMatch(line -> line.contains("with first admin id="));
    }

    // ---------- confined to the new library ----------

    @Test
    void theFirstAdministratorLogsInAndWorksOnlyInsideTheNewLibrary() throws Exception {
        Book creatorsBook = new Book();
        creatorsBook.setTitle("Step152 Creator's Book");
        creatorsBook.setAuthor("Step152 Author");
        creatorsBook.setIsbn("152-" + suffix);
        creatorsBook.setTotalCopies(1);
        creatorsBook.setAvailableCopies(1);
        creatorsBook.setLibrary(ownLibrary);
        bookRepository.save(creatorsBook);

        String username = adminUsername("confined");
        MvcResult created = createLibrary(libraryBody(libraryName("Confined"),
                username, username + "@example.invalid", FIRST_ADMIN_PASSWORD).toString());
        long newLibraryId = json(created).path("id").asLong();
        long newAdminId = json(created).path("admin").path("id").asLong();

        String newAdminToken = login(username, FIRST_ADMIN_PASSWORD);

        // A new library's catalogue is empty; the creator's book is not in it.
        JsonNode books = json(call(get("/api/books"), newAdminToken));
        assertThat(books.path("totalElements").asLong()).isZero();
        assertThat(books.path("content").toString()).doesNotContain("152-" + suffix);

        // Accounts they create land in their library, not the creator's.
        String memberName = "step152-new-member-" + suffix;
        MvcResult member = call(post("/api/users").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.createObjectNode()
                        .put("username", memberName)
                        .put("email", memberName + "@example.invalid")
                        .put("password", "step152-new-member-password")
                        .put("role", "ROLE_MEMBER")
                        .toString()), newAdminToken);
        assertThat(status(member)).isEqualTo(201);
        assertThat(userRepository.findByUsername(memberName).orElseThrow().getLibrary().getId())
                .isEqualTo(newLibraryId);

        // Neither administrator can reach the other library's accounts.
        String disable = "{\"enabled\":false}";
        assertThat(status(call(patch("/api/users/{id}/status", ownMember.getId())
                .contentType(MediaType.APPLICATION_JSON).content(disable), newAdminToken)))
                .as("the new administrator cannot touch the creator's member")
                .isEqualTo(404);
        assertThat(status(call(patch("/api/users/{id}/status", newAdminId)
                .contentType(MediaType.APPLICATION_JSON).content(disable), creatorToken)))
                .as("the creator cannot touch the new administrator")
                .isEqualTo(404);

        assertThat(userRepository.findById(ownMember.getId()).orElseThrow().isEnabled()).isTrue();
        assertThat(userRepository.findById(newAdminId).orElseThrow().isEnabled()).isTrue();
    }

    @Test
    void neitherARoleNorALibraryCanBeChosenForTheFirstAdministrator() throws Exception {
        assertThat(Arrays.stream(FirstAdminRequest.class.getDeclaredFields()).map(Field::getName))
                .as("no role and no library to carry")
                .containsExactlyInAnyOrder("username", "email", "password");
        assertThat(Arrays.stream(CreateLibraryRequest.class.getDeclaredFields()).map(Field::getName))
                .containsExactlyInAnyOrder("name", "admin");

        String username = adminUsername("smuggled");
        ObjectNode body = libraryBody(libraryName("Smuggled"),
                username, username + "@example.invalid", FIRST_ADMIN_PASSWORD);
        ((ObjectNode) body.get("admin"))
                .put("role", "ROLE_MEMBER")
                .put("libraryId", ownLibrary.getId());
        body.put("role", "ROLE_LIBRARIAN").put("libraryId", ownLibrary.getId());

        MvcResult result = createLibrary(body.toString());

        assertThat(status(result)).isEqualTo(201);
        long newLibraryId = json(result).path("id").asLong();

        User stored = userRepository.findByUsername(username).orElseThrow();
        assertThat(stored.getRole()).as("always an administrator").isEqualTo(Role.ROLE_ADMIN);
        assertThat(stored.getLibrary().getId())
                .as("of the new library, never the one named in the body")
                .isEqualTo(newLibraryId)
                .isNotEqualTo(ownLibrary.getId());

        User creatorAfter = userRepository.findById(creator.getId()).orElseThrow();
        assertThat(creatorAfter.getLibrary().getId()).isEqualTo(ownLibrary.getId());
        assertThat(creatorAfter.getRole()).isEqualTo(Role.ROLE_ADMIN);
    }

    // ---------- the same rules as any account ----------

    @Test
    void theAdministratorsFieldsCarryExactlyTheUserEndpointsConstraints() throws Exception {
        for (String field : List.of("username", "email", "password")) {
            assertThat(constraintsOn(FirstAdminRequest.class, field))
                    .as("%s: same annotations, same limits, same messages", field)
                    .isNotEmpty()
                    .isEqualTo(constraintsOn(CreateUserRequest.class, field));
        }
    }

    private static List<String> constraintsOn(Class<?> type, String field) throws NoSuchFieldException {
        return Arrays.stream(type.getDeclaredField(field).getDeclaredAnnotations())
                .map(Annotation::toString)
                .sorted()
                .toList();
    }

    @Test
    void anInvalidOrMissingAdministratorCreatesNeitherLibraryNorAccount() throws Exception {
        String name = libraryName("Invalid");
        String username = adminUsername("invalid");
        String email = username + "@example.invalid";
        long libraries = libraryRepository.count();

        assertError(createLibrary(objectMapper.createObjectNode().put("name", name).toString()),
                400, "First administrator is required");
        assertError(createLibrary(libraryBody(name, username, email, "short").toString()),
                400, "Password must be between 8 and 72 characters");
        assertError(createLibrary(libraryBody(name, username, email, "x".repeat(73)).toString()),
                400, "Password must be between 8 and 72 characters");
        assertError(createLibrary(libraryBody(name, username, "not-an-email", FIRST_ADMIN_PASSWORD).toString()),
                400, "Email must be a valid address");
        assertError(createLibrary(libraryBody(name, "ab", email, FIRST_ADMIN_PASSWORD).toString()),
                400, "Username must be between 3 and 255 characters");
        assertError(createLibrary(libraryBody(name, "   ", email, FIRST_ADMIN_PASSWORD).toString()),
                400, "Username is required");

        assertThat(libraryRepository.count()).isEqualTo(libraries);
        assertThat(libraryRepository.findByName(name)).isEmpty();
        assertThat(userRepository.findByUsername(username)).isEmpty();
    }

    // ---------- atomic ----------

    @Test
    void aTakenUsernameOrEmailRollsTheLibraryBack() throws Exception {
        long step = jdbcTemplate.queryForObject("SELECT @@auto_increment_increment", Long.class);
        String refusedByUsername = libraryName("Taken Username");
        String refusedByEmail = libraryName("Taken Email");

        long before = probeLibraryId("before");

        // The creator's own username, and the member's email in another case.
        assertError(createLibrary(libraryBody(refusedByUsername, creator.getUsername(),
                adminUsername("fresh-a") + "@example.invalid", FIRST_ADMIN_PASSWORD).toString()),
                400, DUPLICATE_ACCOUNT);
        assertError(createLibrary(libraryBody(refusedByEmail, adminUsername("fresh-b"),
                ownMember.getEmail().toUpperCase(Locale.ROOT), FIRST_ADMIN_PASSWORD).toString()),
                400, DUPLICATE_ACCOUNT);

        long after = probeLibraryId("after");

        assertThat(libraryRepository.findByName(refusedByUsername)).as("no library left behind").isEmpty();
        assertThat(libraryRepository.findByName(refusedByEmail)).as("no library left behind").isEmpty();

        // Each refused request consumed one id: its library row really was
        // written, and then rolled back when the account could not be created.
        assertThat(after - before)
                .as("two inserted-then-rolled-back libraries between the probes")
                .isEqualTo(3 * step);

        assertThat(userRepository.findByUsername(adminUsername("fresh-b"))).isEmpty();
        User creatorAfter = userRepository.findById(creator.getId()).orElseThrow();
        assertThat(creatorAfter.getLibrary().getId()).isEqualTo(ownLibrary.getId());
        assertThat(creatorAfter.getRole()).isEqualTo(Role.ROLE_ADMIN);
    }

    @Test
    void aTakenLibraryNameCreatesNoAdministrator() throws Exception {
        String username = adminUsername("orphan");

        assertError(createLibrary(libraryBody(ownLibrary.getName(),
                username, username + "@example.invalid", FIRST_ADMIN_PASSWORD).toString()),
                400, "A library with that name already exists.");

        assertThat(userRepository.findByUsername(username)).isEmpty();
    }

    /** Saves a library directly and returns the id InnoDB gave it. */
    private long probeLibraryId(String label) {
        Library probe = new Library();
        probe.setName(libraryName("Probe " + label));
        return libraryRepository.save(probe).getId();
    }
}
