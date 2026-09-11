package com.library.lms.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.library.lms.entity.Library;
import com.library.lms.entity.Role;
import com.library.lms.entity.User;
import com.library.lms.repository.LibraryRepository;
import com.library.lms.repository.UserRepository;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

/**
 * Proves the token a real login issues carries no role, and that nothing about
 * authorization depended on it carrying one.
 *
 * <p>The {@code roles} claim was written into every token and read by nothing:
 * {@code JwtAuthenticationFilter} takes only the subject and rebuilds the
 * caller's authorities from the database on every request. This class pins both
 * halves of that - the claim is gone from what {@code POST /api/auth/login}
 * hands out, and the database remains the only thing that decides what a
 * caller may do.</p>
 *
 * <p><b>The strongest evidence is a role changed after the token was
 * issued.</b> The same token, presented before and after the row changes, gets
 * a different answer. Nothing inside the token changed, so the answer can only
 * have come from the database. That is also the property the dropped claim
 * could never have provided: a role written into a token is a snapshot, still
 * there after the role is revoked.</p>
 *
 * <p>Driven through the real filter chain with real logins, as
 * {@code SecurityHttpIntegrationTest} is. The application's own signing key is
 * read only to parse issued tokens and to mint the forged one; it is never
 * printed or asserted on.</p>
 *
 * <p><b>Isolation:</b> its own throwaway schema, never the development
 * database. {@code ddl-auto} stays on {@code update}, which can add tables but
 * never drop one, and every fixture carries a unique suffix so repeated runs
 * cannot collide. The role changes below touch only this class's own fixture
 * rows.</p>
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:mysql://localhost:3306/library_db_step138_it"
                + "?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true"
                + "&serverTimezone=UTC",
        "spring.jpa.hibernate.ddl-auto=update",
        "spring.jpa.open-in-view=false"
})
@AutoConfigureMockMvc
class LoginTokenClaimsIntegrationTest {

    /** Test-only credential, never a real one, and never reused outside this class. */
    private static final String TEST_PASSWORD = "step138-test-only-password";

    /** A staff-only endpoint that answers 200 with an empty page for staff. */
    private static final String STAFF_ONLY = "/api/transactions/status/ISSUED";

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

    /** The application's signing key; read to parse and forge, never printed. */
    @Value("${jwt.secret}")
    private String jwtSecret;

    private User member;
    private User librarian;
    private String memberToken;
    private String librarianToken;

    // ---------- fixtures ----------

    private static String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private User persistUser(Library library, Role role, String suffix) {
        String username = "step138-" + role.name().toLowerCase().replace("role_", "") + "-" + suffix;

        User user = new User();
        user.setUsername(username);
        user.setEmail(username + "@example.invalid");
        user.setPassword(passwordEncoder.encode(TEST_PASSWORD));
        user.setFullName("Step138 " + role.name());
        user.setRole(role);
        user.setLibrary(library);

        return userRepository.save(user);
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

        assertThat(result.getResponse().getStatus()).as("login must succeed for %s", username).isEqualTo(200);

        String token = objectMapper.readTree(result.getResponse().getContentAsString()).path("token").asText();
        assertThat(token).as("a token must be issued").isNotBlank();
        return token;
    }

    @BeforeEach
    void createUsersAndLogIn() throws Exception {
        String suffix = unique();

        Library library = new Library();
        library.setName("Step138 Library " + suffix);
        library = libraryRepository.save(library);

        member = persistUser(library, Role.ROLE_MEMBER, suffix);
        librarian = persistUser(library, Role.ROLE_LIBRARIAN, suffix);

        memberToken = login(member.getUsername());
        librarianToken = login(librarian.getUsername());
    }

    // ---------- helpers ----------

    private SecretKey applicationKey() {
        return Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
    }

    /** Parses a token with the application's key, so it also proves the signature. */
    private Claims claimsOf(String token) {
        return Jwts.parser()
                .verifyWith(applicationKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    private int status(MockHttpServletRequestBuilder request, String token) throws Exception {
        return mockMvc.perform(request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andReturn()
                .getResponse()
                .getStatus();
    }

    // ---------- the claim is gone ----------

    @Test
    void aLoginTokenCarriesOnlyTheSubjectAndItsLifetime() {
        // A staff token is the one where a role claim would most tempt someone
        // to trust it, so both are checked.
        for (User account : List.of(member, librarian)) {
            String token = account == member ? memberToken : librarianToken;
            Claims claims = claimsOf(token);

            assertThat(claims.getSubject()).isEqualTo(account.getUsername());
            assertThat(claims).as("no role claim in %s's token", account.getUsername()).doesNotContainKey("roles");
            assertThat(claims.keySet())
                    .as("the complete claim set issued at login")
                    .containsExactlyInAnyOrder("sub", "iat", "exp");
        }
    }

    // ---------- authentication still works ----------

    @Test
    void aTokenWithoutARoleStillAuthenticates() throws Exception {
        assertThat(status(get("/api/books"), memberToken)).as("member, authenticated read").isEqualTo(200);
        assertThat(status(get(STAFF_ONLY), librarianToken)).as("librarian, staff read").isEqualTo(200);
        assertThat(status(get(STAFF_ONLY), memberToken)).as("member, staff read").isEqualTo(403);
    }

    // ---------- the database decides ----------

    @Test
    void aRoleRevokedInTheDatabaseTakesEffectOnAnAlreadyIssuedToken() throws Exception {
        assertThat(status(get(STAFF_ONLY), librarianToken)).as("before: still staff").isEqualTo(200);

        librarian.setRole(Role.ROLE_MEMBER);
        userRepository.save(librarian);

        // Same token, byte for byte. Only the row changed.
        assertThat(status(get(STAFF_ONLY), librarianToken))
                .as("the revocation must apply at once, not when the token expires")
                .isEqualTo(403);
    }

    @Test
    void aRoleGrantedInTheDatabaseTakesEffectOnAnAlreadyIssuedToken() throws Exception {
        assertThat(status(get(STAFF_ONLY), memberToken)).as("before: member").isEqualTo(403);

        member.setRole(Role.ROLE_LIBRARIAN);
        userRepository.save(member);

        assertThat(status(get(STAFF_ONLY), memberToken)).as("same token, role granted in the database").isEqualTo(200);
    }

    // ---------- a roles claim is still not trusted ----------

    @Test
    void aForgedRolesClaimCannotElevateAMember() throws Exception {
        // Signed with the real key, so the signature is genuine and the token
        // authenticates; only the roles claim is a lie. It also stands in for a
        // token issued before this change, which carried a roles claim of its
        // own: such a token must still be accepted, and its claim ignored.
        String forged = Jwts.builder()
                .subject(member.getUsername())
                .claim("roles", List.of("ROLE_ADMIN", "ROLE_LIBRARIAN"))
                .issuedAt(new Date())
                .expiration(Date.from(Instant.now().plus(Duration.ofMinutes(10))))
                .signWith(applicationKey(), Jwts.SIG.HS256)
                .compact();

        assertThat(status(get("/api/books"), forged))
                .as("a token carrying a roles claim is still a valid token")
                .isEqualTo(200);
        assertThat(status(get(STAFF_ONLY), forged))
                .as("but the claim grants nothing - the member's database role does")
                .isEqualTo(403);
    }
}
