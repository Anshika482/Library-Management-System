package com.library.lms.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;
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
 * {@code GET /api/users/me}: every signed-in account can read its own profile,
 * and nothing more.
 *
 * <p>The point of the endpoint is the id: login returns only tokens, and a
 * member needs their own id for their loan history. The last test follows that
 * path end to end. The rest pin down what the endpoint must not become - a way
 * into the staff-only directory, or into anyone else's account.</p>
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
class OwnAccountIntegrationTest {

    /** Test-only credential, never a real one. */
    private static final String PASSWORD = "own-account-test-password";

    private static final Set<String> PUBLIC_FIELDS =
            Set.of("id", "username", "email", "role", "enabled", "accountNonLocked");

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
    private PasswordEncoder passwordEncoder;

    private User admin;
    private User librarian;
    private User member;
    private User otherMember;

    @BeforeEach
    void createAccounts() {
        if (encodedPassword == null) {
            encodedPassword = passwordEncoder.encode(PASSWORD);
        }
        String suffix = UUID.randomUUID().toString().substring(0, 8);

        Library library = new Library();
        library.setName("Own Account Library " + suffix);
        library = libraryRepository.save(library);

        admin = persistUser(library, "me-" + suffix + "-admin", Role.ROLE_ADMIN);
        librarian = persistUser(library, "me-" + suffix + "-librarian", Role.ROLE_LIBRARIAN);
        member = persistUser(library, "me-" + suffix + "-member", Role.ROLE_MEMBER);
        otherMember = persistUser(library, "me-" + suffix + "-other", Role.ROLE_MEMBER);
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

    private String token(User user) throws Exception {
        String body = objectMapper.createObjectNode()
                .put("username", user.getUsername())
                .put("password", PASSWORD)
                .toString();
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("token").asText();
    }

    private MvcResult perform(MockHttpServletRequestBuilder request, String token) throws Exception {
        if (token != null) {
            request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        return mockMvc.perform(request).andReturn();
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private static int status(MvcResult result) {
        return result.getResponse().getStatus();
    }

    @Test
    void everyRoleReadsItsOwnAccount() throws Exception {
        for (User account : List.of(member, librarian, admin)) {
            MvcResult result = perform(get("/api/users/me"), token(account));

            assertThat(status(result)).as(account.getRole().name()).isEqualTo(200);
            JsonNode body = json(result);
            assertThat(body.path("id").asLong()).isEqualTo(account.getId());
            assertThat(body.path("username").asText()).isEqualTo(account.getUsername());
            assertThat(body.path("email").asText()).isEqualTo(account.getEmail());
            assertThat(body.path("role").asText()).isEqualTo(account.getRole().name());
            assertThat(body.path("enabled").asBoolean()).isTrue();
            assertThat(body.path("accountNonLocked").asBoolean()).isTrue();
        }
    }

    @Test
    void theResponseCarriesOnlyPublicFieldsAndNeverTheHash() throws Exception {
        MvcResult result = perform(get("/api/users/me"), token(member));

        Set<String> fields = new TreeSet<>();
        json(result).fieldNames().forEachRemaining(fields::add);
        assertThat(fields).isEqualTo(PUBLIC_FIELDS);
        assertThat(result.getResponse().getContentAsString())
                .doesNotContainIgnoringCase("password")
                .doesNotContain(encodedPassword)
                .doesNotContain("$2");
    }

    @Test
    void anAnonymousCallerIsRefused() throws Exception {
        assertThat(status(perform(get("/api/users/me"), null))).isEqualTo(401);
    }

    @Test
    void headIsServedToAMemberLikeGet() throws Exception {
        assertThat(status(perform(head("/api/users/me"), token(member)))).isEqualTo(200);
    }

    @Test
    void itNeverNamesAnotherAccount() throws Exception {
        MvcResult result = perform(get("/api/users/me")
                .param("userId", String.valueOf(otherMember.getId()))
                .param("id", String.valueOf(otherMember.getId())), token(member));

        assertThat(json(result).path("id").asLong()).as("parameters are ignored").isEqualTo(member.getId());
    }

    @Test
    void theStaffDirectoryStaysClosedToMembers() throws Exception {
        String memberToken = token(member);

        assertThat(status(perform(get("/api/users"), memberToken))).isEqualTo(403);
        assertThat(status(perform(get("/api/users/{id}", otherMember.getId()), memberToken))).isEqualTo(403);
        assertThat(status(perform(get("/api/users/{id}", member.getId()), memberToken)))
                .as("even their own id, by number - that is the directory")
                .isEqualTo(403);
    }

    @Test
    void aMemberCanReachTheirOwnLoanHistoryWithTheIdItGives() throws Exception {
        String memberToken = token(member);

        long ownId = json(perform(get("/api/users/me"), memberToken)).path("id").asLong();

        assertThat(status(perform(get("/api/transactions/user/{userId}", ownId), memberToken)))
                .as("the id from /me opens the member's own loans")
                .isEqualTo(200);
    }
}
