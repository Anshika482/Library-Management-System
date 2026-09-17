package com.library.lms.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.library.lms.entity.Library;
import com.library.lms.entity.Role;
import com.library.lms.entity.User;
import com.library.lms.repository.LibraryRepository;
import com.library.lms.repository.UserRepository;

/**
 * Proves the filter chain's 403 now speaks the same JSON as the rest of the API,
 * and that nothing around it moved.
 *
 * <p>Driven through the real filter chain exactly as
 * {@code SecurityHttpIntegrationTest} is: real users in a real schema, real
 * logins through {@code POST /api/auth/login}, real tokens. Nothing is mocked
 * and no controller is called directly, so a 403 here is one the application
 * would genuinely send.</p>
 *
 * <p><b>Two different 403s, told apart by message.</b> A MEMBER calling a
 * staff-only endpoint is refused by the filter chain: {@code "Access denied."}.
 * A MEMBER reading someone else's loan passes the chain and is refused by the
 * transaction service: {@code "Access denied"}, no full stop, unchanged by this
 * step. Before this step the filter's 403 had no body, and the absence of one
 * was how the two were distinguished. Now both are JSON, so every assertion
 * here compares the message <i>exactly</i> - a substring check would accept
 * either and prove nothing about which path answered.</p>
 *
 * <p><b>Isolation:</b> its own throwaway schema, never the development
 * database. {@code ddl-auto} stays on {@code update}, which can add tables but
 * never drop one, and every fixture carries a unique suffix so repeated runs
 * cannot collide.</p>
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:mysql://localhost:3306/library_db_step135_it"
                + "?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true"
                + "&serverTimezone=UTC",
        "spring.jpa.hibernate.ddl-auto=update",
        "spring.jpa.open-in-view=false"
})
@AutoConfigureMockMvc
class AccessDeniedHandlerIntegrationTest {

    /** Test-only credential, never a real one, and never reused outside this class. */
    private static final String TEST_PASSWORD = "step135-test-only-password";

    /** What the new handler must say. */
    private static final String FILTER_REFUSAL = "Access denied.";

    /** What the transaction service already says, and must keep saying. */
    private static final String SERVICE_REFUSAL = "Access denied";

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

    private String adminToken;
    private String librarianToken;
    private String memberToken;
    private String memberUsername;
    private Long memberId;
    private Long adminId;

    // ---------- fixtures ----------

    private static String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private User persistUser(Library library, Role role, String suffix) {
        String username = "step135-" + role.name().toLowerCase().replace("role_", "") + "-" + suffix;

        User user = new User();
        user.setUsername(username);
        user.setEmail(username + "@example.invalid");
        user.setPassword(passwordEncoder.encode(TEST_PASSWORD));
        user.setFullName("Step135 " + role.name());
        user.setRole(role);
        user.setLibrary(library);

        return userRepository.save(user);
    }

    /** Logs in for real and returns the issued token. */
    private String login(String username) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(credentials(username, TEST_PASSWORD)))
                .andReturn();

        assertThat(result.getResponse().getStatus())
                .as("login must succeed for %s", username)
                .isEqualTo(200);

        String token = json(result).path("token").asText();
        assertThat(token).as("a token must be issued").isNotBlank();
        return token;
    }

    private String credentials(String username, String password) {
        return objectMapper.createObjectNode()
                .put("username", username)
                .put("password", password)
                .toString();
    }

    @BeforeEach
    void createUsersAndLogIn() throws Exception {
        String suffix = unique();

        Library library = new Library();
        library.setName("Step135 Library " + suffix);
        library = libraryRepository.save(library);

        User admin = persistUser(library, Role.ROLE_ADMIN, suffix);
        User librarian = persistUser(library, Role.ROLE_LIBRARIAN, suffix);
        User member = persistUser(library, Role.ROLE_MEMBER, suffix);

        adminId = admin.getId();
        memberId = member.getId();
        memberUsername = member.getUsername();

        adminToken = login(admin.getUsername());
        librarianToken = login(librarian.getUsername());
        memberToken = login(memberUsername);
    }

    // ---------- helpers ----------

    private MvcResult perform(MockHttpServletRequestBuilder request, String token) throws Exception {
        if (token != null) {
            request = request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        return mockMvc.perform(request).andReturn();
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private void assertJsonContentType(String label, MvcResult result) {
        String contentType = result.getResponse().getContentType();

        assertThat(contentType).as("%s must declare a content type", label).isNotNull();
        MediaType mediaType = MediaType.parseMediaType(contentType);
        assertThat(mediaType.getType() + "/" + mediaType.getSubtype())
                .as("%s content type", label)
                .isEqualTo(MediaType.APPLICATION_JSON_VALUE);
    }

    /**
     * Asserts the exact shape the new handler must produce: 403, JSON, the
     * three {@code ErrorResponse} fields and nothing else, and the fixed
     * message.
     */
    private void assertStandardFilterRefusal(String label, MvcResult result) throws Exception {
        assertThat(result.getResponse().getStatus()).as("%s status", label).isEqualTo(403);
        assertJsonContentType(label, result);

        JsonNode body = json(result);
        List<String> fields = new ArrayList<>();
        body.fieldNames().forEachRemaining(fields::add);

        assertThat(fields)
                .as("%s must carry exactly the ErrorResponse fields", label)
                .containsExactlyInAnyOrder("status", "message", "timestamp");
        assertThat(body.path("status").asInt()).as("%s JSON status", label).isEqualTo(403);
        assertThat(body.path("message").asText()).as("%s message", label).isEqualTo(FILTER_REFUSAL);
        assertThat(body.path("timestamp").asText()).as("%s timestamp", label).isNotBlank();
    }

    /** Every staff-only matcher in SecurityConfig, across all four methods. */
    private List<MockHttpServletRequestBuilder> staffOnlyRequests() {
        return List.of(
                get("/api/transactions/status/ISSUED"),
                get("/api/transactions/book/999999"),
                post("/api/books").contentType(MediaType.APPLICATION_JSON).content("{}"),
                put("/api/books/999999").contentType(MediaType.APPLICATION_JSON).content("{}"),
                delete("/api/books/999999"),
                post("/api/categories").contentType(MediaType.APPLICATION_JSON).content("{}"),
                delete("/api/categories/999999"),
                post("/api/transactions/issue").contentType(MediaType.APPLICATION_JSON).content("{}"),
                post("/api/transactions/999999/return"),
                post("/api/transactions/999999/fine-payment"));
    }

    // ---------- A: the new 403 ----------

    @Test
    void aMemberOnAStaffOnlyEndpointGetsTheStandardJsonRefusal() throws Exception {
        MvcResult result = perform(get("/api/transactions/status/ISSUED"), memberToken);

        assertStandardFilterRefusal("MEMBER on GET /status", result);
    }

    @Test
    void everyStaffOnlyEndpointRefusesAMemberInTheSameShape() throws Exception {
        // The handler is chain-wide, not attached to one rule. Every staff-only
        // matcher - GET, POST, PUT and DELETE, across books, categories and
        // transactions - must answer identically.
        for (MockHttpServletRequestBuilder request : staffOnlyRequests()) {
            MvcResult result = perform(request, memberToken);
            String label = result.getRequest().getMethod() + " " + result.getRequest().getRequestURI();

            assertStandardFilterRefusal(label, result);
        }
    }

    @Test
    void theRefusalDisclosesNothingAboutTheRuleThatRefusedIt() throws Exception {
        // Knowing which authority was wanted tells a caller which role to go
        // looking for; knowing the path or the exception type describes the
        // implementation. None of it belongs in the answer.
        for (MockHttpServletRequestBuilder request : staffOnlyRequests()) {
            MvcResult result = perform(request, memberToken);
            String body = result.getResponse().getContentAsString().toLowerCase();

            assertThat(body)
                    .as("refusal body for %s", result.getRequest().getRequestURI())
                    .doesNotContain("exception")
                    .doesNotContain("role_")
                    .doesNotContain("authorit")
                    .doesNotContain("trace")
                    .doesNotContain("org.springframework")
                    .doesNotContain("/api/")
                    .doesNotContain(memberUsername.toLowerCase());
        }
    }

    // ---------- B: staff are unaffected ----------

    @Test
    void librarianAndAdminStillReachTheSameEndpoint() throws Exception {
        // The endpoint that refused the MEMBER above. Staff get a real page -
        // empty, since this library has no loans - which proves the handler
        // changed the refusal and nothing about who is refused.
        for (String token : List.of(librarianToken, adminToken)) {
            MvcResult result = perform(get("/api/transactions/status/ISSUED"), token);

            assertThat(result.getResponse().getStatus()).isEqualTo(200);
            assertThat(json(result).has("content")).as("a paged response").isTrue();
        }
    }

    @Test
    void aStaffWriteStillPassesSecurityAndReachesTheService() throws Exception {
        // 404 is the proof: security let it through and the service then found
        // no such book. A 403 here would mean the handler or a rule had moved.
        MvcResult result = perform(delete("/api/books/999999"), librarianToken);

        assertThat(result.getResponse().getStatus()).isEqualTo(404);
    }

    // ---------- C: unauthenticated callers still get 401 ----------

    @Test
    void anAnonymousRequestStillGetsTheExisting401() throws Exception {
        // ExceptionTranslationFilter sends an anonymous caller to the entry
        // point, not to the access-denied handler. If that routing were ever
        // lost, this would come back as the new 403.
        for (MockHttpServletRequestBuilder request : List.of(
                get("/api/transactions/status/ISSUED"),
                post("/api/books").contentType(MediaType.APPLICATION_JSON).content("{}"),
                get("/api/books"))) {
            MvcResult result = perform(request, null);
            String label = "anonymous " + result.getRequest().getMethod() + " " + result.getRequest().getRequestURI();

            assertThat(result.getResponse().getStatus()).as(label).isEqualTo(401);
            assertJsonContentType(label, result);
            assertThat(json(result).path("message").asText()).as(label).isEqualTo("Authentication required");
        }
    }

    @Test
    void anInvalidTokenStillGetsTheExisting401() throws Exception {
        MvcResult result = perform(get("/api/transactions/status/ISSUED"), "not.a.real.token");

        assertThat(result.getResponse().getStatus()).isEqualTo(401);
        assertThat(json(result).path("message").asText()).isEqualTo("Authentication required");
    }

    @Test
    void aFailedLoginStillGetsTheExisting401() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(credentials(memberUsername, "not-the-password")))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(401);
        assertThat(json(result).path("message").asText()).isEqualTo("Invalid username or password");
    }

    // ---------- D: the service's own 403 is untouched ----------

    @Test
    void theTransactionServicesOwnershipRefusalIsUnchanged() throws Exception {
        // Both requests pass the filter chain - GET /api/transactions/** is open
        // to any authenticated user - and are refused by the service instead.
        // The exact message, without the full stop, is what proves which path
        // answered.
        for (MockHttpServletRequestBuilder request : List.of(
                get("/api/transactions/999999"),
                get("/api/transactions/user/" + adminId))) {
            MvcResult result = perform(request, memberToken);
            String label = "service refusal " + result.getRequest().getRequestURI();

            assertThat(result.getResponse().getStatus()).as(label).isEqualTo(403);
            assertThat(json(result).path("message").asText())
                    .as("%s must still come from the transaction service", label)
                    .isEqualTo(SERVICE_REFUSAL)
                    .isNotEqualTo(FILTER_REFUSAL);
        }
    }

    @Test
    void aMemberStillReadsTheirOwnHistory() throws Exception {
        // The control for the test above: the ownership rule refuses other
        // people's loans, not all of them.
        MvcResult result = perform(get("/api/transactions/user/" + memberId), memberToken);

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
    }
}
