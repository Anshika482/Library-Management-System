package com.library.lms.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.filter.CorsFilter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.library.lms.config.RestCorsProcessor;
import com.library.lms.entity.Library;
import com.library.lms.entity.Role;
import com.library.lms.entity.User;
import com.library.lms.repository.CategoryRepository;
import com.library.lms.repository.LibraryRepository;
import com.library.lms.repository.UserRepository;

/**
 * Proves CORS through the real security chain, with two origins configured.
 *
 * <p><b>Preflights are answered, not authenticated.</b> A browser's preflight
 * never carries a token; before this, every one was refused with a 401 and no
 * browser client could call the API at all.</p>
 *
 * <p><b>Only the listed origins, exactly.</b> Near misses - the other scheme,
 * another port, a subdomain, the listed host as a prefix of a longer one, the
 * {@code null} origin - are all refused, and no response ever allows every
 * origin or credentials.</p>
 *
 * <p><b>A refused request is not carried out.</b> A category created from an
 * unlisted origin, with a perfectly valid librarian's token, does not
 * exist afterwards; the same request from a listed origin does.</p>
 *
 * <p><b>Nothing else changes.</b> Authentication and role failures keep their
 * statuses and messages - and carry the CORS headers, so a browser client can
 * read them - while requests with no Origin header, or from the API's own
 * origin, behave exactly as they did.</p>
 *
 * <p><b>Isolation:</b> the throwaway schema the other integration tests use,
 * never the development database, with a unique suffix per test.</p>
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:mysql://localhost:3306/library_db_step129_it"
                + "?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true"
                + "&serverTimezone=UTC",
        "spring.jpa.hibernate.ddl-auto=update",
        "spring.jpa.open-in-view=false",
        "security.cors.allowed-origins=https://app.example.test, http://localhost:5173"
})
@AutoConfigureMockMvc
class CorsIntegrationTest {

    private static final String APP = "https://app.example.test";

    private static final String LOCAL_APP = "http://localhost:5173";

    private static final String REFUSED = "Cross-origin request not allowed.";

    /** Test-only credential, never a real one, and never reused outside this class. */
    private static final String TEST_PASSWORD = "step154-test-only-password";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private LibraryRepository libraryRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private ApplicationContext applicationContext;

    private String suffix;
    private Library library;
    private String memberToken;
    private String librarianToken;

    // ---------- fixtures ----------

    @BeforeEach
    void createLibraryAndAccounts() throws Exception {
        suffix = UUID.randomUUID().toString().substring(0, 8);

        Library created = new Library();
        created.setName("Step154 Library " + suffix);
        library = libraryRepository.save(created);

        memberToken = login(persistUser(Role.ROLE_MEMBER, "member"));
        librarianToken = login(persistUser(Role.ROLE_LIBRARIAN, "librarian"));
    }

    private User persistUser(Role role, String label) {
        String username = "step154-" + label + "-" + suffix;

        User user = new User();
        user.setUsername(username);
        user.setEmail(username + "@example.invalid");
        user.setPassword(passwordEncoder.encode(TEST_PASSWORD));
        user.setFullName("Step154 " + label);
        user.setRole(role);
        user.setLibrary(library);
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

        assertThat(result.getResponse().getStatus()).as("login for %s", user.getUsername()).isEqualTo(200);
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("token").asText();
    }

    private MvcResult preflight(String path, String origin, String method, String requestHeaders) throws Exception {
        MockHttpServletRequestBuilder request = options(path)
                .header(HttpHeaders.ORIGIN, origin)
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, method);
        if (requestHeaders != null) {
            request.header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, requestHeaders);
        }
        return mockMvc.perform(request).andReturn();
    }

    private MvcResult perform(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request).andReturn();
    }

    private static String header(MvcResult result, String name) {
        return result.getResponse().getHeader(name);
    }

    private static Set<String> headerList(MvcResult result, String name) {
        String value = header(result, name);
        return value == null ? Set.of() : Arrays.stream(value.split(","))
                .map(item -> item.trim().toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    /** Refused by CORS: the API's error shape, and nothing that would let a page read it. */
    private void assertRefusedByCors(String label, MvcResult result) throws Exception {
        assertThat(result.getResponse().getStatus()).as(label).isEqualTo(403);
        assertThat(result.getResponse().getContentType()).as(label).startsWith(MediaType.APPLICATION_JSON_VALUE);
        assertThat(json(result).path("status").asInt()).as(label).isEqualTo(403);
        assertThat(json(result).path("message").asText()).as(label).isEqualTo(REFUSED);
        assertThat(header(result, HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)).as(label).isNull();
    }

    /** Allowed for exactly this origin - never every origin, never with credentials. */
    private static void assertAllowedFor(String label, MvcResult result, String origin) {
        assertThat(header(result, HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)).as(label).isEqualTo(origin);
        assertThat(header(result, HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS)).as(label).isNull();
        assertThat(String.join(",", result.getResponse().getHeaders(HttpHeaders.VARY))).as(label).contains("Origin");
    }

    // ---------- one filter ----------

    @Test
    void exactlyOneCorsFilterRunsAndItAnswersInTheApisErrorShape() {
        FilterChainProxy chains = applicationContext.getBean("springSecurityFilterChain", FilterChainProxy.class);

        List<CorsFilter> corsFilters = chains.getFilterChains().stream()
                .flatMap(chain -> chain.getFilters().stream())
                .filter(CorsFilter.class::isInstance)
                .map(CorsFilter.class::cast)
                .toList();

        assertThat(corsFilters)
                .as("one CORS filter in the security chain, not a second one built by Spring Security")
                .hasSize(1);
        assertThat(ReflectionTestUtils.getField(corsFilters.get(0), "processor"))
                .isInstanceOf(RestCorsProcessor.class);
        assertThat(applicationContext.getBean("corsFilterRegistration", FilterRegistrationBean.class).isEnabled())
                .as("and not run a second time by the servlet container")
                .isFalse();
    }

    // ---------- preflight ----------

    @Test
    void aPreflightFromAListedOriginIsAnsweredWithoutAToken() throws Exception {
        for (String origin : List.of(APP, LOCAL_APP)) {
            MvcResult result = preflight("/api/books", origin, "GET", "authorization");

            assertThat(result.getResponse().getStatus()).as(origin).isEqualTo(200);
            assertAllowedFor(origin, result, origin);
            assertThat(headerList(result, HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS)).containsExactly("authorization");
            assertThat(header(result, HttpHeaders.ACCESS_CONTROL_MAX_AGE)).isEqualTo("3600");
            assertThat(result.getResponse().getContentAsString()).as("a preflight has no body").isEmpty();
        }
    }

    @Test
    void everyMethodTheApiUsesIsAllowedWithAuthorizationAndJson() throws Exception {
        List<String[]> calls = List.of(
                new String[] {"GET", "/api/books"},
                new String[] {"POST", "/api/books"},
                new String[] {"PUT", "/api/books/1"},
                new String[] {"PATCH", "/api/users/1/status"},
                new String[] {"DELETE", "/api/categories/1"});

        for (String[] call : calls) {
            MvcResult result = preflight(call[1], APP, call[0], "Authorization, Content-Type");

            assertThat(result.getResponse().getStatus()).as("%s %s", call[0], call[1]).isEqualTo(200);
            assertAllowedFor(call[0], result, APP);
            assertThat(headerList(result, HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS))
                    .containsExactlyInAnyOrder("get", "head", "post", "put", "patch", "delete");
            assertThat(headerList(result, HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS))
                    .containsExactlyInAnyOrder("authorization", "content-type");
        }
    }

    @Test
    void aPreflightFromAnyOtherOriginIsRefused() throws Exception {
        for (String origin : List.of(
                "https://evil.example.test",
                "http://app.example.test",
                "https://app.example.test:8443",
                "https://sub.app.example.test",
                "https://app.example.test.evil.example",
                "http://localhost:3000",
                "null")) {
            assertRefusedByCors(origin, preflight("/api/books", origin, "GET", "authorization"));
        }
    }

    @Test
    void aPreflightAskingForAnUnlistedMethodOrOnlyUnlistedHeadersIsRefused() throws Exception {
        assertRefusedByCors("TRACE", preflight("/api/books", APP, "TRACE", null));
        assertRefusedByCors("X-Api-Key", preflight("/api/books", APP, "GET", "x-api-key"));
    }

    @Test
    void aPreflightMixingListedAndUnlistedHeadersIsAllowedOnlyTheListedOnes() throws Exception {
        // Spring answers with the requested headers it allows rather than
        // refusing the whole preflight, and the browser then declines to send
        // the actual request because X-Api-Key is not among them. What matters
        // is that the unlisted header is never in the answer.
        MvcResult result = preflight("/api/books", APP, "GET", "authorization, x-api-key");

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertAllowedFor("mixed headers", result, APP);
        assertThat(headerList(result, HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS)).containsExactly("authorization");
    }

    // ---------- actual requests ----------

    @Test
    void anAuthenticatedRequestFromAListedOriginCarriesTheCorsHeaders() throws Exception {
        MvcResult result = perform(get("/api/books")
                .header(HttpHeaders.ORIGIN, APP)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + memberToken));

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertAllowedFor("GET /api/books", result, APP);
    }

    @Test
    void authenticationAndRoleFailuresAreUnchangedAndReadableByTheListedOrigin() throws Exception {
        MvcResult noToken = perform(get("/api/books").header(HttpHeaders.ORIGIN, APP));
        assertThat(noToken.getResponse().getStatus()).isEqualTo(401);
        assertThat(json(noToken).path("message").asText()).isEqualTo("Authentication required");
        assertAllowedFor("no token", noToken, APP);

        MvcResult badToken = perform(get("/api/books")
                .header(HttpHeaders.ORIGIN, APP)
                .header(HttpHeaders.AUTHORIZATION, "Bearer not-a-token"));
        assertThat(badToken.getResponse().getStatus()).isEqualTo(401);
        assertAllowedFor("bad token", badToken, APP);

        MvcResult wrongRole = perform(post("/api/books")
                .header(HttpHeaders.ORIGIN, APP)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + memberToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"));
        assertThat(wrongRole.getResponse().getStatus()).as("a member still may not add books").isEqualTo(403);
        assertThat(json(wrongRole).path("message").asText()).isEqualTo("Access denied.");
        assertAllowedFor("wrong role", wrongRole, APP);
    }

    @Test
    void aRequestFromAnUnlistedOriginIsRefusedBeforeItIsCarriedOut() throws Exception {
        String name = "Step154 Category " + suffix;
        String body = objectMapper.createObjectNode().put("name", name).toString();

        MvcResult refused = perform(post("/api/categories")
                .header(HttpHeaders.ORIGIN, "https://evil.example.test")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + librarianToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));

        assertRefusedByCors("unlisted origin, valid librarian token", refused);
        assertThat(categoryRepository.existsByLibraryIdAndNameIgnoreCase(library.getId(), name))
                .as("the category was never created")
                .isFalse();

        MvcResult accepted = perform(post("/api/categories")
                .header(HttpHeaders.ORIGIN, APP)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + librarianToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));

        assertThat(accepted.getResponse().getStatus()).as("the same request from a listed origin").isEqualTo(201);
        assertAllowedFor("listed origin", accepted, APP);
        assertThat(categoryRepository.existsByLibraryIdAndNameIgnoreCase(library.getId(), name)).isTrue();
    }

    @Test
    void requestsWithoutAnOriginOrFromTheApisOwnOriginAreUnaffected() throws Exception {
        MvcResult noOrigin = perform(get("/api/books").header(HttpHeaders.AUTHORIZATION, "Bearer " + memberToken));
        MvcResult sameOrigin = perform(get("/api/books")
                .header(HttpHeaders.ORIGIN, "http://localhost")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + memberToken));

        for (MvcResult result : List.of(noOrigin, sameOrigin)) {
            assertThat(result.getResponse().getStatus()).isEqualTo(200);
            assertThat(header(result, HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN))
                    .as("not a cross-origin request")
                    .isNull();
        }

        assertThat(perform(get("/api/books")).getResponse().getStatus())
                .as("and still no way in without a token")
                .isEqualTo(401);
    }
}
