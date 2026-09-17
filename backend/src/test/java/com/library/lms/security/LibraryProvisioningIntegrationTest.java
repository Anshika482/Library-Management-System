package com.library.lms.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
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
import com.library.lms.dto.CreateLibraryRequest;
import com.library.lms.entity.Library;
import com.library.lms.entity.Role;
import com.library.lms.entity.User;
import com.library.lms.repository.LibraryRepository;
import com.library.lms.repository.UserRepository;

/**
 * Proves an administrator can register a new library, and that nobody else can
 * - the one way a second tenant used to arrive was a row inserted by hand.
 *
 * <p><b>The request can name the library and nothing more.</b> A body carrying
 * an id, a role or the creator's own id is accepted for its name and the rest
 * is ignored, and that is asserted, not assumed: the id sent is an existing
 * library's, so honouring it would visibly overwrite that library.</p>
 *
 * <p><b>The creator gains nothing.</b> Their account is read back after each
 * creation and must still belong to the same library with the same role.</p>
 *
 * <p><b>Nothing is created on refusal.</b> Every refused request - a duplicate,
 * an invalid name, a caller without the authority - is followed by a check
 * that the number of libraries did not change.</p>
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
class LibraryProvisioningIntegrationTest {

    /** Test-only credential, never a real one, and never reused outside this class. */
    private static final String TEST_PASSWORD = "step151-test-only-password";

    private static final String DUPLICATE_MESSAGE = "A library with that name already exists.";

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
    private Library ownLibrary;
    private User admin;
    private String adminToken;
    private String librarianToken;
    private String memberToken;

    // ---------- fixtures ----------

    @BeforeEach
    void createLibraryAndStaff() throws Exception {
        suffix = UUID.randomUUID().toString().substring(0, 8);

        Library library = new Library();
        library.setName("Step151 Own Library " + suffix);
        ownLibrary = libraryRepository.save(library);

        admin = persistUser(Role.ROLE_ADMIN, "admin");
        User librarian = persistUser(Role.ROLE_LIBRARIAN, "librarian");
        User member = persistUser(Role.ROLE_MEMBER, "member");

        adminToken = login(admin);
        librarianToken = login(librarian);
        memberToken = login(member);
    }

    private User persistUser(Role role, String label) {
        String username = "step151-" + label + "-" + suffix;

        User user = new User();
        user.setUsername(username);
        user.setEmail(username + "@example.invalid");
        user.setPassword(passwordEncoder.encode(TEST_PASSWORD));
        user.setFullName("Step151 " + label);
        user.setRole(role);
        user.setLibrary(ownLibrary);
        return userRepository.save(user);
    }

    // ---------- helpers ----------

    private String login(User user) throws Exception {
        String body = objectMapper.createObjectNode()
                .put("username", user.getUsername())
                .put("password", TEST_PASSWORD)
                .toString();

        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();

        assertThat(status(result)).as("login for %s", user.getUsername()).isEqualTo(200);
        return json(result).path("token").asText();
    }

    private String nameJson(String name) {
        return objectMapper.createObjectNode().put("name", name).toString();
    }

    private MvcResult createLibrary(String body, String token) throws Exception {
        return mockMvc.perform(post("/api/libraries")
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

    /** A refusal in the shape every error in this API takes. */
    private void assertError(MvcResult result, int expectedStatus, String expectedMessage) throws Exception {
        assertThat(status(result)).isEqualTo(expectedStatus);

        JsonNode body = json(result);
        assertThat(body.path("status").asInt()).isEqualTo(expectedStatus);
        assertThat(body.path("message").asText()).isEqualTo(expectedMessage);
        assertThat(body.path("timestamp").isMissingNode()).as("timestamp present").isFalse();
    }

    // ---------- creating a library ----------

    @Test
    void anAdministratorCreatesALibrary() throws Exception {
        String name = "Step151 New Library " + suffix;
        long before = libraryRepository.count();

        MvcResult result = createLibrary(nameJson("  " + name + "  "), adminToken);

        assertThat(status(result)).isEqualTo(201);
        JsonNode body = json(result);

        List<String> fields = new ArrayList<>();
        body.fieldNames().forEachRemaining(fields::add);
        assertThat(fields).containsExactlyInAnyOrder("id", "name", "createdAt");

        assertThat(body.path("name").asText()).as("stored trimmed").isEqualTo(name);
        assertThat(body.path("createdAt").isTextual()).isTrue();

        Library stored = libraryRepository.findById(body.path("id").asLong()).orElseThrow();
        assertThat(stored.getName()).isEqualTo(name);
        assertThat(stored.getCreatedAt()).isNotNull();
        assertThat(libraryRepository.count()).isEqualTo(before + 1);
    }

    @Test
    void aNameOfExactlyOneHundredCharactersIsAccepted() throws Exception {
        String prefix = "Step151 Boundary " + suffix + " ";
        String name = prefix + "x".repeat(100 - prefix.length());
        assertThat(name).hasSize(100);

        assertThat(status(createLibrary(nameJson(name), adminToken))).isEqualTo(201);
    }

    // ---------- the request cannot reach beyond a name ----------

    @Test
    void theRequestCanNeitherChooseAnIdNorAttachItsCreator() throws Exception {
        assertThat(Arrays.stream(CreateLibraryRequest.class.getDeclaredFields()).map(Field::getName))
                .as("a library is described by its name alone")
                .containsExactly("name");

        // An existing library's id: if it were honoured, saving would overwrite
        // that library instead of creating one.
        long existingId = ownLibrary.getId();
        String name = "Step151 Smuggled " + suffix;
        String body = objectMapper.createObjectNode()
                .put("name", name)
                .put("id", existingId)
                .put("libraryId", existingId)
                .put("userId", admin.getId())
                .put("adminId", admin.getId())
                .put("role", "ROLE_ADMIN")
                .toString();

        MvcResult result = createLibrary(body, adminToken);

        assertThat(status(result)).isEqualTo(201);
        assertThat(json(result).path("id").asLong()).as("the database assigns the id").isNotEqualTo(existingId);
        assertThat(libraryRepository.findById(existingId).orElseThrow().getName())
                .as("the existing library was not overwritten")
                .isEqualTo(ownLibrary.getName());

        User creator = userRepository.findById(admin.getId()).orElseThrow();
        assertThat(creator.getLibrary().getId())
                .as("the creator is not moved into the new library")
                .isEqualTo(ownLibrary.getId());
        assertThat(creator.getRole()).isEqualTo(Role.ROLE_ADMIN);
        assertThat(creator.isEnabled()).isTrue();
        assertThat(creator.isAccountNonLocked()).isTrue();
    }

    // ---------- duplicate names ----------

    @Test
    void aNameAlreadyInUseIsRefusedHoweverItIsTyped() throws Exception {
        String name = "Step151 Duplicate " + suffix;
        assertThat(status(createLibrary(nameJson(name), adminToken))).isEqualTo(201);
        long before = libraryRepository.count();

        for (String attempt : List.of(name, name.toUpperCase(Locale.ROOT), "   " + name + " ")) {
            assertError(createLibrary(nameJson(attempt), adminToken), 400, DUPLICATE_MESSAGE);
        }

        assertThat(libraryRepository.count()).as("nothing was created").isEqualTo(before);
    }

    @Test
    void theNameOfALibraryThatAlreadyExistedIsRefused() throws Exception {
        // The fixture library was not made through this endpoint: the check is
        // against every library, not only the ones created here.
        long before = libraryRepository.count();

        assertError(createLibrary(nameJson(ownLibrary.getName()), adminToken), 400, DUPLICATE_MESSAGE);

        assertThat(libraryRepository.count()).isEqualTo(before);
    }

    // ---------- validation ----------

    @Test
    void aMissingBlankOrOverlongNameIsRefused() throws Exception {
        long before = libraryRepository.count();

        assertError(createLibrary("{}", adminToken), 400, "Library name is required");
        assertError(createLibrary(nameJson("   "), adminToken), 400, "Library name is required");
        assertError(createLibrary(nameJson("x".repeat(101)), adminToken), 400,
                "Library name must not exceed 100 characters");
        assertError(createLibrary("{\"name\":", adminToken), 400,
                "Request body could not be read. Check that it is valid JSON.");

        assertThat(libraryRepository.count()).as("nothing was created").isEqualTo(before);
    }

    // ---------- who may do it ----------

    @Test
    void aCallerWithoutATokenIsRefused() throws Exception {
        String name = "Step151 Anonymous " + suffix;
        long before = libraryRepository.count();

        MvcResult result = mockMvc.perform(post("/api/libraries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(nameJson(name)))
                .andReturn();

        assertError(result, 401, "Authentication required");
        assertThat(libraryRepository.count()).isEqualTo(before);
        assertThat(libraryRepository.findByName(name)).isEmpty();
    }

    @Test
    void neitherAMemberNorALibrarianMayCreateALibrary() throws Exception {
        String name = "Step151 Forbidden " + suffix;
        long before = libraryRepository.count();

        for (String token : List.of(memberToken, librarianToken)) {
            assertError(createLibrary(nameJson(name), token), 403, "Access denied.");

            // Every verb on the path is administrators only, not just POST: a
            // non-admin is refused before routing, so even an unmapped method
            // answers 403 rather than 405.
            MvcResult read = mockMvc.perform(get("/api/libraries")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                    .andReturn();
            assertThat(status(read)).isEqualTo(403);
        }

        assertThat(libraryRepository.count()).isEqualTo(before);
        assertThat(libraryRepository.findByName(name)).isEmpty();
    }
}
