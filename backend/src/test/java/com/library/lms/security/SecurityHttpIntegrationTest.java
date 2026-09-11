package com.library.lms.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.crypto.SecretKey;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

/**
 * Drives the real Spring Security filter chain over HTTP semantics.
 *
 * <p>Until now every security guarantee in this project was argued from source
 * or proven one layer below the boundary that enforces it. {@code JwtServiceTest}
 * checks token mechanics; the service tests check tenancy with Mockito. Nothing
 * checked the thing that actually decides access: the fifteen matchers in
 * {@code SecurityConfig} and the filter in front of them. Nine endpoints rely on
 * those matchers alone - their services carry no role check at all - so a
 * reordered rule or a widened pattern would have been silently undetectable.</p>
 *
 * <p><b>Nothing here is mocked.</b> MockMvc dispatches through the real filter
 * chain; the users are real rows; the tokens come from real
 * {@code POST /api/auth/login} calls with real BCrypt password checks. Where a
 * token has to be malformed, expired or forged, it is minted here with jjwt
 * rather than by weakening production code.</p>
 *
 * <p><b>What "allowed" asserts.</b> These are authorization tests, not business
 * tests. A staff request to {@code DELETE /api/books/999999} should get past
 * security and then fail as 404 - that 404 is the proof. So "allowed" means
 * <i>not 401 and not 403</i>, which is exactly the security question and stays
 * true regardless of what the service does with the request.</p>
 *
 * <p><b>Isolation:</b> its own throwaway schema, never the development database.
 * {@code ddl-auto} stays on {@code update}, which can add tables but never drop
 * one, and every fixture carries a unique suffix so repeated runs cannot
 * collide.</p>
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:mysql://localhost:3306/library_db_step129_it"
                + "?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true"
                + "&serverTimezone=UTC",
        "spring.jpa.hibernate.ddl-auto=update",
        "spring.jpa.open-in-view=false"
})
@AutoConfigureMockMvc
class SecurityHttpIntegrationTest {

    /** Test-only credential, never a real one, and never reused outside this class. */
    private static final String TEST_PASSWORD = "step129-test-only-password";

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

    /**
     * The signing key the application itself uses.
     *
     * <p>Read so this class can mint the tokens a real client could plausibly
     * present - one expired, one carrying a forged role - without touching
     * production code. The value is never printed or asserted on.</p>
     */
    @Value("${jwt.secret}")
    private String jwtSecret;

    /**
     * The issuer and audience the application demands. Every hand-built token
     * below carries the right pair, so each one is refused - or, for the forged
     * role, accepted - for its own reason and not for a missing claim.
     */
    @Value("${jwt.issuer}")
    private String jwtIssuer;

    @Value("${jwt.audience}")
    private String jwtAudience;

    private String adminToken;
    private String librarianToken;
    private String memberToken;
    private String memberUsername;

    // ---------- fixtures ----------

    private static String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private String persistUser(Library library, Role role, String suffix) {
        String username = "step129-" + role.name().toLowerCase().replace("role_", "") + "-" + suffix;

        User user = new User();
        user.setUsername(username);
        user.setEmail(username + "@example.invalid");
        user.setPassword(passwordEncoder.encode(TEST_PASSWORD));
        user.setFullName("Step129 " + role.name());
        user.setRole(role);
        user.setLibrary(library);
        userRepository.save(user);

        return username;
    }

    /** Logs in for real and returns the issued token. */
    private String login(String username) throws Exception {
        String body = objectMapper.createObjectNode()
                .put("username", username)
                .put("password", TEST_PASSWORD)
                .toString();

        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();

        assertThat(result.getResponse().getStatus())
                .as("login must succeed for %s", username)
                .isEqualTo(200);

        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        String token = json.path("token").asText();
        assertThat(token).as("a token must be issued").isNotBlank();
        return token;
    }

    @BeforeEach
    void createUsersAndLogIn() throws Exception {
        String suffix = unique();

        Library library = new Library();
        library.setName("Step129 Library " + suffix);
        library = libraryRepository.save(library);

        String adminUsername = persistUser(library, Role.ROLE_ADMIN, suffix);
        String librarianUsername = persistUser(library, Role.ROLE_LIBRARIAN, suffix);
        memberUsername = persistUser(library, Role.ROLE_MEMBER, suffix);

        adminToken = login(adminUsername);
        librarianToken = login(librarianUsername);
        memberToken = login(memberUsername);
    }

    // ---------- helpers ----------

    private SecretKey applicationKey() {
        return Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
    }

    private int status(MockHttpServletRequestBuilder request, String token) throws Exception {
        if (token != null) {
            request = request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        return mockMvc.perform(request).andReturn().getResponse().getStatus();
    }

    /** Past security: neither an authentication nor an authorization refusal. */
    private void assertAllowed(String label, MockHttpServletRequestBuilder request, String token)
            throws Exception {
        int status = status(request, token);
        assertThat(status)
                .as("%s must pass security (any non-401/403 status proves it reached the app)", label)
                .isNotIn(401, 403);
    }

    private void assertForbidden(String label, MockHttpServletRequestBuilder request, String token)
            throws Exception {
        assertThat(status(request, token)).as("%s must be refused with 403", label).isEqualTo(403);
    }

    private void assertUnauthorized(String label, MockHttpServletRequestBuilder request, String token)
            throws Exception {
        assertThat(status(request, token)).as("%s must be refused with 401", label).isEqualTo(401);
    }

    /** Every staff-only matcher, as a request the service will reject harmlessly. */
    private List<MockHttpServletRequestBuilder> staffOnlyRequests() {
        String bookJson = "{\"title\":\"T\",\"author\":\"A\",\"isbn\":\"step129\",\"totalCopies\":1}";
        String categoryJson = "{\"name\":\"Step129 Cat\"}";
        String issueJson = "{\"bookId\":999999,\"dueDate\":\"2099-01-01\"}";

        return List.of(
                post("/api/books").contentType(MediaType.APPLICATION_JSON).content(bookJson),
                put("/api/books/999999").contentType(MediaType.APPLICATION_JSON).content(bookJson),
                delete("/api/books/999999"),
                post("/api/categories").contentType(MediaType.APPLICATION_JSON).content(categoryJson),
                put("/api/categories/999999").contentType(MediaType.APPLICATION_JSON).content(categoryJson),
                delete("/api/categories/999999"),
                post("/api/transactions/issue").contentType(MediaType.APPLICATION_JSON).content(issueJson),
                post("/api/transactions/999999/return"),
                get("/api/transactions/book/999999"),
                get("/api/transactions/status/ISSUED"));
    }

    /**
     * The matchers any authenticated user may reach, restricted to endpoints
     * whose services impose no further rule.
     *
     * <p>{@code GET /api/transactions/{id}} is deliberately absent: a MEMBER may
     * reach it, but the service then answers 403 under its own ownership rule,
     * which a "not 403" assertion cannot distinguish from a filter refusal. It
     * is covered separately, by the shape of the body rather than the status.</p>
     */
    private List<MockHttpServletRequestBuilder> authenticatedGetRequests() {
        return List.of(
                get("/api/books"),
                get("/api/books/999999"),
                get("/api/books/search?keyword=step129"),
                get("/api/categories"));
    }

    // ---------- 1-3: anonymous ----------

    @Test
    void anonymousRequestToAProtectedEndpointIsUnauthorized() throws Exception {
        assertUnauthorized("anonymous GET /api/books", get("/api/books"), null);
        assertUnauthorized("anonymous GET /api/categories", get("/api/categories"), null);
        assertUnauthorized("anonymous GET /api/transactions/1", get("/api/transactions/1"), null);
        assertUnauthorized("anonymous POST /api/books",
                post("/api/books").contentType(MediaType.APPLICATION_JSON).content("{}"), null);
    }

    @Test
    void getOnTheLoginEndpointIsUnauthorized() throws Exception {
        // Only POST /api/auth/login is permitAll; GET falls through to anyRequest().
        assertUnauthorized("GET /api/auth/login", get("/api/auth/login"), null);
    }

    @Test
    void anUnknownEndpointIsUnauthorizedRatherThanOpen() throws Exception {
        // anyRequest().authenticated() must fail closed, so a future endpoint
        // added without a matcher is never accidentally public.
        assertUnauthorized("anonymous GET /api/does-not-exist", get("/api/does-not-exist"), null);
        assertUnauthorized("anonymous GET /internal/metrics", get("/internal/metrics"), null);
    }

    // ---------- 4-6: the roles that should be allowed ----------

    @Test
    void adminMayReachEveryStaffOnlyEndpoint() throws Exception {
        for (MockHttpServletRequestBuilder request : staffOnlyRequests()) {
            assertAllowed("ADMIN staff request", request, adminToken);
        }
    }

    @Test
    void librarianMayReachEveryStaffOnlyEndpoint() throws Exception {
        for (MockHttpServletRequestBuilder request : staffOnlyRequests()) {
            assertAllowed("LIBRARIAN staff request", request, librarianToken);
        }
    }

    @Test
    void memberMayReachAuthenticatedGetEndpoints() throws Exception {
        for (MockHttpServletRequestBuilder request : authenticatedGetRequests()) {
            assertAllowed("MEMBER authenticated GET", request, memberToken);
        }
    }

    @Test
    void memberReachesTheTransactionServiceRatherThanBeingStoppedByTheFilter() throws Exception {
        // Matcher 14 (GET /api/transactions/** authenticated) lets a MEMBER
        // through; the service's ownership rule then refuses this particular
        // loan. Both refusals are 403, so the status alone cannot tell them
        // apart - the body can. A service refusal carries the project's
        // ErrorResponse JSON; a filter refusal carries no body at all.
        MvcResult result = mockMvc.perform(get("/api/transactions/999999")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + memberToken))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        assertThat(result.getResponse().getContentAsString())
                .as("a JSON body proves the request reached the service, not stopped at the chain")
                .contains("\"status\":403")
                .contains("Access denied");
    }

    // ---------- 7: the role that should not ----------

    @Test
    void memberIsForbiddenFromEveryStaffOnlyEndpoint() throws Exception {
        for (MockHttpServletRequestBuilder request : staffOnlyRequests()) {
            assertForbidden("MEMBER staff request", request, memberToken);
        }
    }

    // ---------- 7b: staff-only reads, by every method ----------

    /** The two read paths only staff may use. */
    private static List<String> staffOnlyReadPaths() {
        return List.of("/api/transactions/status/ISSUED", "/api/transactions/book/999999");
    }

    /**
     * GET and HEAD on each staff-only read path, keyed "METHOD path", so a
     * failure shows every combination at once rather than stopping at the first.
     */
    private Map<String, Integer> getAndHeadStatuses(String token) throws Exception {
        Map<String, Integer> statuses = new LinkedHashMap<>();
        for (String path : staffOnlyReadPaths()) {
            statuses.put("GET " + path, status(get(path), token));
            statuses.put("HEAD " + path, status(head(path), token));
        }
        return statuses;
    }

    private static Map<String, Integer> every(int expectedStatus) {
        Map<String, Integer> statuses = new LinkedHashMap<>();
        for (String path : staffOnlyReadPaths()) {
            statuses.put("GET " + path, expectedStatus);
            statuses.put("HEAD " + path, expectedStatus);
        }
        return statuses;
    }

    @Test
    void aMemberIsForbiddenFromStaffOnlyReadsByGetAndByHead() throws Exception {
        // HEAD is the case that slipped through. The rules for these paths named
        // GET, HEAD is not GET, and Spring MVC answers HEAD from the GET handler -
        // so a member's HEAD fell through to authenticated() and ran the
        // staff-only query. The body is dropped for HEAD; the query still ran.
        assertThat(getAndHeadStatuses(memberToken)).containsExactlyEntriesOf(every(403));
    }

    @Test
    void staffStillReachStaffOnlyReadsByGetAndByHead() throws Exception {
        assertThat(getAndHeadStatuses(librarianToken)).as("librarian").containsExactlyEntriesOf(every(200));
        assertThat(getAndHeadStatuses(adminToken)).as("admin").containsExactlyEntriesOf(every(200));
    }

    @Test
    void anonymousCallersStillGet401OnStaffOnlyReadsByGetAndByHead() throws Exception {
        assertThat(getAndHeadStatuses(null)).containsExactlyEntriesOf(every(401));
    }

    // ---------- 8-10: bad tokens ----------

    @Test
    void anExpiredJwtIsUnauthorized() throws Exception {
        Instant past = Instant.now().minus(2, ChronoUnit.HOURS);
        String expired = Jwts.builder()
                .subject(memberUsername)
                .issuer(jwtIssuer)
                .audience().add(jwtAudience).and()
                .claim("roles", List.of("ROLE_MEMBER"))
                .issuedAt(Date.from(past))
                .expiration(Date.from(past.plus(1, ChronoUnit.HOURS)))
                .signWith(applicationKey(), Jwts.SIG.HS256)
                .compact();

        assertUnauthorized("expired JWT", get("/api/books"), expired);
    }

    @Test
    void aJwtSignedWithADifferentKeyIsUnauthorized() throws Exception {
        SecretKey otherKey = Keys.hmacShaKeyFor(
                "a-completely-different-key-of-sufficient-length-32".getBytes(StandardCharsets.UTF_8));
        String wronglySigned = Jwts.builder()
                .subject(memberUsername)
                .issuer(jwtIssuer)
                .audience().add(jwtAudience).and()
                .expiration(Date.from(Instant.now().plus(1, ChronoUnit.HOURS)))
                .signWith(otherKey, Jwts.SIG.HS256)
                .compact();

        assertUnauthorized("wrong-signature JWT", get("/api/books"), wronglySigned);
    }

    @Test
    void aTamperedJwtIsUnauthorized() throws Exception {
        // Change the FIRST character of a genuine token's signature. Not the
        // last: an HS256 signature is 32 bytes, 43 Base64URL characters, and the
        // final character carries only 4 signature bits plus 2 padding bits.
        // Swapping 'A' for 'B' there touches padding alone, so about one run in
        // sixteen the "tampered" token still verified and this test failed.
        // Every bit of the first character is signature data.
        int signatureStart = memberToken.lastIndexOf('.') + 1;
        char first = memberToken.charAt(signatureStart);
        String tampered = memberToken.substring(0, signatureStart)
                + (first == 'A' ? 'B' : 'A')
                + memberToken.substring(signatureStart + 1);
        assertThat(tampered).as("the tamper must actually change the token").isNotEqualTo(memberToken);

        assertUnauthorized("tampered JWT", get("/api/books"), tampered);
    }

    @Test
    void malformedAuthorizationHeadersAreUnauthorized() throws Exception {
        assertUnauthorized("malformed JWT", get("/api/books"), "not.a.jwt");
        assertUnauthorized("empty bearer token", get("/api/books"), "");

        // Headers the helper cannot express: a non-Bearer scheme, and Bearer
        // with no separating space.
        assertThat(mockMvc.perform(get("/api/books")
                        .header(HttpHeaders.AUTHORIZATION, "Basic YWRtaW46eA=="))
                .andReturn().getResponse().getStatus())
                .as("non-Bearer scheme").isEqualTo(401);

        assertThat(mockMvc.perform(get("/api/books")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer"))
                .andReturn().getResponse().getStatus())
                .as("Bearer with no token").isEqualTo(401);
    }

    // ---------- 11: the claim must not be trusted ----------

    @Test
    void aForgedAdminRoleClaimDoesNotElevateAMember() throws Exception {
        // Correctly signed with the application's own key, so the signature is
        // valid and the token parses. Only the roles claim is a lie.
        String forged = Jwts.builder()
                .subject(memberUsername)
                .issuer(jwtIssuer)
                .audience().add(jwtAudience).and()
                .claim("roles", List.of("ROLE_ADMIN", "ROLE_LIBRARIAN"))
                .issuedAt(Date.from(Instant.now()))
                .expiration(Date.from(Instant.now().plus(1, ChronoUnit.HOURS)))
                .signWith(applicationKey(), Jwts.SIG.HS256)
                .compact();

        // Authenticates as the member - the subject is real - but the authority
        // comes from the database row, not the claim.
        assertAllowed("forged-claim token on an authenticated GET", get("/api/books"), forged);

        for (MockHttpServletRequestBuilder request : staffOnlyRequests()) {
            assertForbidden("forged ROLE_ADMIN claim", request, forged);
        }
    }

    // ---------- 12: capture, do not fix ----------

    @Test
    void captureTheForbiddenResponseShape() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/transactions/status/ISSUED")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + memberToken))
                .andReturn();

        int status = result.getResponse().getStatus();
        String contentType = result.getResponse().getContentType();
        String body = result.getResponse().getContentAsString();
        String errorMessage = result.getResponse().getErrorMessage();

        System.out.println("=== STEP 129: observed 403 response ===");
        System.out.println("  status       : " + status);
        System.out.println("  content-type : " + contentType);
        System.out.println("  errorMessage : " + errorMessage);
        System.out.println("  body length  : " + body.length());
        System.out.println("  body         : [" + body + "]");
        System.out.println("=== end ===");

        // Recorded, not corrected. Step 128 F4 noted no accessDeniedHandler is
        // configured; whatever shape this is, fixing it is a separate decision.
        assertThat(status).isEqualTo(403);
    }
}
