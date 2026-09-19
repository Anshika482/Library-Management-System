package com.library.lms.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.ArrayList;
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
 * The user directory over HTTP: who may read it, what they see, and what they
 * never see.
 *
 * <p><b>Two libraries per test.</b> Library A has an administrator, a librarian
 * and four members - two ordinary, one disabled, one locked. Library B has an
 * administrator and a member whose name deliberately echoes one of A's, so a
 * search that leaked across libraries would find it. Every test starts from
 * fresh libraries, so A's totals are exact however many accounts the shared
 * schema already holds.</p>
 *
 * <p><b>Usernames and emails differ on purpose</b>, so a search can be shown to
 * match the email alone.</p>
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
class UserDirectoryIntegrationTest {

    /** Test-only credential, never a real one. */
    private static final String TEST_PASSWORD = "directory-test-only-password";

    /** The only fields an account response may carry. */
    private static final Set<String> PUBLIC_FIELDS =
            Set.of("id", "username", "email", "role", "enabled", "accountNonLocked");

    /** Hashed once: BCrypt is slow on purpose, and every fixture account shares the password. */
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

    private String suffix;

    private User adminA;
    private User librarianA;
    private User memberAlpha;
    private User memberBeta;
    private User memberDisabled;
    private User memberLocked;
    private User adminB;
    private User memberB;

    private String adminAToken;
    private String librarianAToken;
    private String memberToken;
    private String adminBToken;

    // ---------- fixtures ----------

    @BeforeEach
    void createTwoLibraries() throws Exception {
        if (encodedPassword == null) {
            encodedPassword = passwordEncoder.encode(TEST_PASSWORD);
        }
        suffix = UUID.randomUUID().toString().substring(0, 8);

        Library libraryA = newLibrary("A");
        adminA = persistUser(libraryA, "a", Role.ROLE_ADMIN, "admin", true, true);
        librarianA = persistUser(libraryA, "a", Role.ROLE_LIBRARIAN, "librarian", true, true);
        memberAlpha = persistUser(libraryA, "a", Role.ROLE_MEMBER, "member-alpha", true, true);
        memberBeta = persistUser(libraryA, "a", Role.ROLE_MEMBER, "member-beta", true, true);
        memberDisabled = persistUser(libraryA, "a", Role.ROLE_MEMBER, "member-disabled", false, true);
        memberLocked = persistUser(libraryA, "a", Role.ROLE_MEMBER, "member-locked", true, false);

        Library libraryB = newLibrary("B");
        adminB = persistUser(libraryB, "b", Role.ROLE_ADMIN, "admin", true, true);
        memberB = persistUser(libraryB, "b", Role.ROLE_MEMBER, "member-alpha", true, true);

        adminAToken = login(adminA);
        librarianAToken = login(librarianA);
        memberToken = login(memberAlpha);
        adminBToken = login(adminB);
    }

    private Library newLibrary(String label) {
        Library library = new Library();
        library.setName("Directory Library " + label + " " + suffix);
        return libraryRepository.save(library);
    }

    private User persistUser(Library library, String libraryLabel, Role role, String label, boolean enabled,
            boolean accountNonLocked) {
        User user = new User();
        user.setUsername("dir-" + suffix + "-" + libraryLabel + "-" + label);
        user.setEmail("contact-" + label + "-" + suffix + "-" + libraryLabel + "@example.invalid");
        user.setPassword(encodedPassword);
        user.setRole(role);
        user.setLibrary(library);
        user.setEnabled(enabled);
        user.setAccountNonLocked(accountNonLocked);
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
                .content(body)).andReturn();

        assertThat(result.getResponse().getStatus()).as("login for %s", user.getUsername()).isEqualTo(200);
        return json(result).path("token").asText();
    }

    private MvcResult perform(MockHttpServletRequestBuilder request, String token) throws Exception {
        if (token != null) {
            request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        return mockMvc.perform(request).andReturn();
    }

    /** GET /api/users with query parameters given as name, value, name, value... */
    private MvcResult list(String token, String... params) throws Exception {
        MockHttpServletRequestBuilder request = get("/api/users");
        for (int i = 0; i < params.length; i += 2) {
            request.param(params[i], params[i + 1]);
        }
        return perform(request, token);
    }

    private MvcResult view(Long id, String token) throws Exception {
        return perform(get("/api/users/{id}", id), token);
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private static int status(MvcResult result) {
        return result.getResponse().getStatus();
    }

    private List<String> usernames(MvcResult result) throws Exception {
        List<String> names = new ArrayList<>();
        json(result).path("content").forEach(user -> names.add(user.path("username").asText()));
        return names;
    }

    private static List<String> usernamesOf(User... users) {
        List<String> names = new ArrayList<>();
        for (User user : users) {
            names.add(user.getUsername());
        }
        return names;
    }

    // ---------- who may read it ----------

    @Test
    void anAnonymousCallerIsRefusedWith401() throws Exception {
        assertThat(status(list(null))).isEqualTo(401);
        assertThat(status(view(memberAlpha.getId(), null))).isEqualTo(401);
    }

    @Test
    void aMemberHasNoDirectoryAccessByAnyReadingVerb() throws Exception {
        assertThat(status(list(memberToken))).as("list").isEqualTo(403);
        assertThat(status(view(memberBeta.getId(), memberToken))).as("another member").isEqualTo(403);
        assertThat(status(view(memberAlpha.getId(), memberToken))).as("even themselves").isEqualTo(403);
        assertThat(status(perform(head("/api/users"), memberToken)))
                .as("HEAD is served by the GET handler, so it must be refused too")
                .isEqualTo(403);
        assertThat(status(perform(head("/api/users/{id}", memberBeta.getId()), memberToken))).isEqualTo(403);
    }

    @Test
    void aMemberIsStoppedByTheFilterChainBeforeReachingTheDirectory() throws Exception {
        // Members are refused twice over: by the filter chain, and again by
        // UserService. Either alone keeps them out, which is exactly why a
        // status code cannot tell whether the first lock still works. The
        // chain's refusal carries its own fixed sentence - with the full stop -
        // while the service's does not, so the sentence says which lock
        // answered. UserDirectoryServiceTest covers the second lock alone.
        for (MvcResult refused : List.of(list(memberToken), view(memberBeta.getId(), memberToken))) {
            assertThat(status(refused)).isEqualTo(403);
            assertThat(json(refused).path("message").asText())
                    .as("refused in the filter chain, never reaching the controller")
                    .isEqualTo("Access denied.");
        }
    }

    @Test
    void anAdministratorListsEveryAccountOfTheirLibrary() throws Exception {
        MvcResult result = list(adminAToken, "size", "50");

        assertThat(status(result)).isEqualTo(200);
        assertThat(json(result).path("totalElements").asLong()).isEqualTo(6);
        assertThat(usernames(result)).containsExactlyInAnyOrderElementsOf(
                usernamesOf(adminA, librarianA, memberAlpha, memberBeta, memberDisabled, memberLocked));
    }

    @Test
    void aLibrarianListsMembersOnly() throws Exception {
        MvcResult result = list(librarianAToken, "size", "50");

        assertThat(status(result)).isEqualTo(200);
        assertThat(json(result).path("totalElements").asLong()).as("no staff counted either").isEqualTo(4);
        assertThat(usernames(result)).containsExactlyInAnyOrderElementsOf(
                usernamesOf(memberAlpha, memberBeta, memberDisabled, memberLocked));
        json(result).path("content").forEach(user -> assertThat(user.path("role").asText()).isEqualTo("ROLE_MEMBER"));
    }

    @Test
    void aLibrarianAskingForStaffIsRefusedNotQuietlyAnswered() throws Exception {
        for (String staffRole : List.of("ROLE_ADMIN", "ROLE_LIBRARIAN")) {
            MvcResult refused = list(librarianAToken, "role", staffRole);

            assertThat(status(refused)).as(staffRole).isEqualTo(403);
            assertThat(json(refused).path("message").asText()).isEqualTo("Access denied");
            assertThat(refused.getResponse().getContentAsString())
                    .as("the refusal names nobody")
                    .doesNotContain(adminA.getUsername())
                    .doesNotContain(librarianA.getUsername());
        }

        MvcResult members = list(librarianAToken, "role", "ROLE_MEMBER", "size", "50");
        assertThat(status(members)).isEqualTo(200);
        assertThat(json(members).path("totalElements").asLong()).isEqualTo(4);
    }

    @Test
    void aLibrarianCanViewAMemberButNoStaffAccountNotEvenTheirOwn() throws Exception {
        assertThat(status(view(memberAlpha.getId(), librarianAToken))).isEqualTo(200);
        assertThat(status(view(memberDisabled.getId(), librarianAToken)))
                .as("any member, active or not")
                .isEqualTo(200);

        assertThat(status(view(adminA.getId(), librarianAToken)))
                .as("staff look exactly like a missing id to a librarian")
                .isEqualTo(404);
        assertThat(status(view(librarianA.getId(), librarianAToken))).isEqualTo(404);
    }

    @Test
    void anAdministratorCanViewAnyAccountOfTheirLibrary() throws Exception {
        for (User user : List.of(adminA, librarianA, memberAlpha, memberLocked)) {
            MvcResult result = view(user.getId(), adminAToken);

            assertThat(status(result)).as(user.getUsername()).isEqualTo(200);
            assertThat(json(result).path("username").asText()).isEqualTo(user.getUsername());
            assertThat(json(result).path("role").asText()).isEqualTo(user.getRole().name());
        }
    }

    @Test
    void readingIsOpenedToLibrariansButWritingIsNot() throws Exception {
        assertThat(status(perform(head("/api/users"), librarianAToken))).as("HEAD alongside GET").isEqualTo(200);

        String newUser = objectMapper.createObjectNode()
                .put("username", "dir-" + suffix + "-created-by-librarian")
                .put("email", "created-by-librarian-" + suffix + "@example.invalid")
                .put("password", TEST_PASSWORD)
                .put("role", "ROLE_MEMBER")
                .toString();
        assertThat(status(perform(post("/api/users").contentType(MediaType.APPLICATION_JSON).content(newUser),
                librarianAToken))).as("creating accounts stays administrators only").isEqualTo(403);

        String disable = objectMapper.createObjectNode().put("enabled", false).toString();
        assertThat(status(perform(patch("/api/users/{id}/status", memberAlpha.getId())
                .contentType(MediaType.APPLICATION_JSON).content(disable), librarianAToken)))
                .as("changing status stays administrators only")
                .isEqualTo(403);
        assertThat(userRepository.findById(memberAlpha.getId()).orElseThrow().isEnabled()).isTrue();

        assertThat(status(perform(get("/api/users/{id}/status", memberAlpha.getId()), librarianAToken)))
                .as("the one-segment GET rule does not reach the status path")
                .isEqualTo(403);
    }

    // ---------- strict library isolation ----------

    @Test
    void aListNeverReachesAnotherLibrary() throws Exception {
        MvcResult searchForB = list(adminAToken, "keyword", memberB.getUsername());
        assertThat(status(searchForB)).isEqualTo(200);
        assertThat(json(searchForB).path("totalElements").asLong())
                .as("library B's member, found by its exact username, from library A")
                .isZero();

        MvcResult echoed = list(adminAToken, "keyword", "member-alpha", "size", "50");
        assertThat(usernames(echoed))
                .as("both libraries have a member-alpha; only A's is A's to see")
                .containsExactly(memberAlpha.getUsername());

        MvcResult libraryB = list(adminBToken, "size", "50");
        assertThat(json(libraryB).path("totalElements").asLong()).isEqualTo(2);
        assertThat(usernames(libraryB)).containsExactlyInAnyOrderElementsOf(usernamesOf(adminB, memberB));
    }

    @Test
    void anotherLibrarysAccountLooksExactlyLikeAMissingOne() throws Exception {
        assertThat(status(view(memberB.getId(), adminAToken))).as("another library's member").isEqualTo(404);
        assertThat(status(view(adminB.getId(), adminAToken))).as("another library's administrator").isEqualTo(404);
        assertThat(status(view(memberB.getId(), librarianAToken))).isEqualTo(404);
        assertThat(status(view(Long.MAX_VALUE, adminAToken))).as("an id that exists nowhere").isEqualTo(404);
    }

    // ---------- pagination ----------

    @Test
    void pagesAreSizedAndCountedWithinTheLibrary() throws Exception {
        MvcResult first = list(adminAToken, "size", "2", "page", "0");
        assertThat(json(first).path("content").size()).isEqualTo(2);
        assertThat(json(first).path("page").asInt()).isZero();
        assertThat(json(first).path("size").asInt()).isEqualTo(2);
        assertThat(json(first).path("totalElements").asLong()).isEqualTo(6);
        assertThat(json(first).path("totalPages").asInt()).isEqualTo(3);

        List<String> seen = new ArrayList<>();
        for (int page = 0; page < 3; page++) {
            seen.addAll(usernames(list(adminAToken, "size", "2", "page", String.valueOf(page))));
        }
        assertThat(seen).as("three pages, every account exactly once").hasSize(6).doesNotHaveDuplicates();

        assertThat(json(list(adminAToken, "size", "2", "page", "3")).path("content").size())
                .as("past the last page")
                .isZero();
    }

    @Test
    void theSameFiftyAccountCeilingAsEveryOtherList() throws Exception {
        assertThat(status(list(adminAToken, "size", "50"))).isEqualTo(200);
        assertThat(status(list(adminAToken, "size", "51"))).isEqualTo(400);
        assertThat(status(list(adminAToken, "size", "0"))).isEqualTo(400);
        assertThat(status(list(adminAToken, "page", "-1"))).isEqualTo(400);
    }

    @Test
    void sortingIsLimitedToPublicFieldsAndIsDeterministic() throws Exception {
        List<String> descending =
                usernames(list(adminAToken, "size", "50", "sortBy", "username", "direction", "DESC"));
        List<String> expected = new ArrayList<>(descending);
        expected.sort(java.util.Comparator.reverseOrder());
        assertThat(descending).isEqualTo(expected);

        assertThat(status(list(adminAToken, "sortBy", "password")))
                .as("no sorting - and so no ordering oracle - on the hash")
                .isEqualTo(400);
        assertThat(status(list(adminAToken, "sortBy", "library"))).isEqualTo(400);
        assertThat(status(list(adminAToken, "direction", "sideways"))).isEqualTo(400);
    }

    // ---------- search and filters ----------

    @Test
    void aKeywordMatchesUsernamesCaseInsensitively() throws Exception {
        MvcResult result = list(adminAToken, "keyword", "  MEMBER-BETA  ");

        assertThat(usernames(result)).containsExactly(memberBeta.getUsername());
    }

    @Test
    void aKeywordMatchesEmails() throws Exception {
        String emailOnly = "contact-member-locked-" + suffix;
        assertThat(memberLocked.getUsername()).doesNotContain(emailOnly);

        assertThat(usernames(list(adminAToken, "keyword", emailOnly))).containsExactly(memberLocked.getUsername());
    }

    @Test
    void filtersNarrowAndCombine() throws Exception {
        assertThat(usernames(list(adminAToken, "enabled", "false")))
                .containsExactly(memberDisabled.getUsername());
        assertThat(usernames(list(adminAToken, "accountNonLocked", "false")))
                .containsExactly(memberLocked.getUsername());
        assertThat(usernames(list(adminAToken, "role", "ROLE_LIBRARIAN")))
                .containsExactly(librarianA.getUsername());
        assertThat(usernames(list(adminAToken, "role", "ROLE_MEMBER", "enabled", "true", "accountNonLocked", "true")))
                .as("every filter must match")
                .containsExactlyInAnyOrderElementsOf(usernamesOf(memberAlpha, memberBeta));
        assertThat(usernames(list(librarianAToken, "enabled", "false")))
                .as("a librarian's filters apply within members")
                .containsExactly(memberDisabled.getUsername());
        assertThat(json(list(adminAToken, "keyword", "   ")).path("totalElements").asLong())
                .as("a blank keyword filters nothing")
                .isEqualTo(6);
    }

    @Test
    void anUnknownRoleOrMalformedFlagIsABadRequest() throws Exception {
        assertThat(status(list(adminAToken, "role", "ROLE_SUPERUSER"))).isEqualTo(400);
        assertThat(status(list(adminAToken, "enabled", "maybe"))).isEqualTo(400);
        assertThat(status(perform(get("/api/users/{id}", "abc"), adminAToken))).isEqualTo(400);
        assertThat(status(view(0L, adminAToken))).as("ids are positive").isEqualTo(400);
    }

    // ---------- what a response may carry ----------

    @Test
    void responsesCarryOnlyPublicAccountFieldsAndNeverAHash() throws Exception {
        MvcResult listed = list(adminAToken, "size", "50");
        MvcResult viewed = view(librarianA.getId(), adminAToken);
        String hash = userRepository.findById(librarianA.getId()).orElseThrow().getPassword();

        json(listed).path("content").forEach(user -> assertThat(fieldNames(user)).isEqualTo(PUBLIC_FIELDS));
        assertThat(fieldNames(json(viewed))).isEqualTo(PUBLIC_FIELDS);

        for (MvcResult result : List.of(listed, viewed)) {
            String body = result.getResponse().getContentAsString();
            assertThat(body)
                    .doesNotContainIgnoringCase("password")
                    .doesNotContain(hash)
                    .doesNotContain("$2a$")
                    .doesNotContain("$2b$")
                    .doesNotContain("$2y$")
                    .doesNotContainIgnoringCase("library")
                    .doesNotContainIgnoringCase("token");
        }
    }

    private static Set<String> fieldNames(JsonNode node) {
        Set<String> names = new TreeSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
