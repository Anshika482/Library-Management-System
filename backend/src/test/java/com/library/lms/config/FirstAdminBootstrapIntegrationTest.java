package com.library.lms.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.library.lms.dto.CreateLibraryRequest;
import com.library.lms.dto.FirstAdminRequest;
import com.library.lms.entity.Library;
import com.library.lms.entity.Role;
import com.library.lms.entity.User;
import com.library.lms.exception.DuplicateAccountException;
import com.library.lms.repository.LibraryRepository;
import com.library.lms.repository.UserRepository;
import com.library.lms.service.LibraryService;

/**
 * The bootstrap against a real, genuinely empty database.
 *
 * <p><b>A schema of its own, created for this run.</b> The shared test schema
 * has accounts in it, which is exactly the case where the bootstrap must do
 * nothing - so proving it does something needs a database no test has touched.
 * The schema is named after a random suffix, created by the driver on connect,
 * and dropped when the class finishes.</p>
 *
 * <p>The context starts with the bootstrap settings configured, so by the time
 * the first test runs the administrator has already been created: the
 * assertions below are about what startup left behind.</p>
 */
@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=update",
        "spring.jpa.open-in-view=false"
})
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FirstAdminBootstrapIntegrationTest {

    private static final String SCHEMA = "library_db_bootstrap_" + UUID.randomUUID().toString().substring(0, 8);

    private static final String LIBRARY = "Bootstrapped Central Library";

    private static final String USERNAME = "bootstrap-first-admin";

    private static final String EMAIL = "bootstrap-first-admin@example.invalid";

    /** Test-only credential, never a real one. */
    private static final String PASSWORD = "bootstrap-test-only-password";

    @DynamicPropertySource
    static void emptySchemaAndBootstrapSettings(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:mysql://localhost:3306/" + SCHEMA
                + "?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC");
        registry.add(FirstAdminBootstrap.LIBRARY_PROPERTY, () -> LIBRARY);
        registry.add(FirstAdminBootstrap.USERNAME_PROPERTY, () -> USERNAME);
        registry.add(FirstAdminBootstrap.EMAIL_PROPERTY, () -> EMAIL);
        registry.add(FirstAdminBootstrap.PASSWORD_PROPERTY, () -> PASSWORD);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private LibraryRepository libraryRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private LibraryService libraryService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterAll
    void dropTheThrowawaySchema() {
        jdbcTemplate.execute("DROP DATABASE IF EXISTS " + SCHEMA);
    }

    // ---------- what startup left behind ----------

    @Test
    void anEmptyDatabaseGetsExactlyOneLibraryAndOneAdministrator() {
        List<Library> libraries = libraryRepository.findAll();
        List<User> users = userRepository.findAll();

        assertThat(libraries).hasSize(1);
        assertThat(libraries.get(0).getName()).isEqualTo(LIBRARY);
        assertThat(users).hasSize(1);
        assertThat(users.get(0).getUsername()).isEqualTo(USERNAME);
        assertThat(users.get(0).getEmail()).isEqualTo(EMAIL);
        assertThat(users.get(0).getLibrary().getId())
                .as("in the library created alongside it, not some other one")
                .isEqualTo(libraries.get(0).getId());
    }

    @Test
    void theAdministratorHasTheAdminRoleAndIsUsable() {
        User admin = userRepository.findByUsername(USERNAME).orElseThrow();

        assertThat(admin.getRole()).isEqualTo(Role.ROLE_ADMIN);
        assertThat(admin.isEnabled()).isTrue();
        assertThat(admin.isAccountNonLocked()).isTrue();
    }

    @Test
    void thePasswordIsStoredOnlyAsABcryptHash() {
        User admin = userRepository.findByUsername(USERNAME).orElseThrow();

        assertThat(admin.getPassword()).as("never the password itself").isNotEqualTo(PASSWORD);
        assertThat(admin.getPassword()).as("the application's own encoder").startsWith("$2");
        assertThat(passwordEncoder.matches(PASSWORD, admin.getPassword()))
                .as("and it is a hash of that password")
                .isTrue();
    }

    @Test
    void theBootstrappedAdministratorCanLogIn() throws Exception {
        String body = objectMapper.createObjectNode()
                .put("username", USERNAME)
                .put("password", PASSWORD)
                .toString();

        MvcResult result = mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)).andReturn();

        assertThat(result.getResponse().getStatus()).as("the whole point: someone can now sign in").isEqualTo(200);
        assertThat(objectMapper.readTree(result.getResponse().getContentAsString()).path("token").asText())
                .isNotBlank();
    }

    // ---------- it does not run twice ----------

    @Test
    void aSecondRunOverANonEmptyTableChangesNothing() {
        long librariesBefore = libraryRepository.count();
        long usersBefore = userRepository.count();

        // The bean the context built, running again exactly as it would at the
        // next start - against the database its first run populated.
        new FirstAdminBootstrap(userRepository, libraryService, null, LIBRARY, USERNAME, EMAIL, PASSWORD).run(null);

        assertThat(libraryRepository.count()).isEqualTo(librariesBefore);
        assertThat(userRepository.count()).isEqualTo(usersBefore);
    }

    // ---------- the pair is atomic ----------

    @Test
    void aLibraryIsNeverLeftBehindWhenItsAdministratorCannotBeCreated() {
        long librariesBefore = libraryRepository.count();

        FirstAdminRequest clashing = new FirstAdminRequest();
        clashing.setUsername(USERNAME);
        clashing.setEmail("second-" + EMAIL);
        clashing.setPassword(PASSWORD);

        CreateLibraryRequest request = new CreateLibraryRequest();
        request.setName("A Second Library");
        request.setAdmin(clashing);

        assertThatThrownBy(() -> libraryService.createFirstLibrary(request))
                .isInstanceOf(DuplicateAccountException.class);

        assertThat(libraryRepository.count())
                .as("the library row is written first, so only a rollback can remove it")
                .isEqualTo(librariesBefore);
        assertThat(libraryRepository.existsByNameIgnoreCase("A Second Library")).isFalse();
    }
}
