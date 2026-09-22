package com.library.lms.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
import com.library.lms.entity.Book;
import com.library.lms.entity.Library;
import com.library.lms.entity.Role;
import com.library.lms.entity.User;
import com.library.lms.repository.BookRepository;
import com.library.lms.repository.DigitalResourceRepository;
import com.library.lms.repository.LibraryRepository;
import com.library.lms.repository.UserRepository;

/**
 * Digital resources over HTTP: who may change them, who may see them, and
 * whose library's they are.
 *
 * <p><b>Two libraries</b>, because the property worth proving here is that a
 * resource cannot be read, edited or deleted from another library by naming its
 * id - and that a disabled one is invisible to a member rather than forbidden,
 * which would tell them it exists.</p>
 *
 * <p><b>The same context settings as the other account tests</b>, so this class
 * reuses that cached Spring context rather than starting another one - the
 * suite is close to the database's connection limit.</p>
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
class DigitalResourceApiIntegrationTest {

    /** Test-only credential, never a real one. */
    private static final String PASSWORD = "digital-resource-test-password";

    private static final String URL = "https://files.example.invalid/chapter-one.pdf";

    private static final List<String> RESPONSE_FIELDS = List.of("id", "bookId", "bookTitle", "title",
            "description", "resourceType", "resourceUrl", "enabled", "createdAt", "updatedAt");

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
    private BookRepository bookRepository;

    @Autowired
    private DigitalResourceRepository digitalResourceRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private record Tenant(Library library, Long bookId, String adminToken, String librarianToken,
                          String memberToken) {
    }

    private Tenant a;
    private Tenant b;

    @BeforeEach
    void createTwoLibraries() throws Exception {
        if (encodedPassword == null) {
            encodedPassword = passwordEncoder.encode(PASSWORD);
        }
        a = tenant("A");
        b = tenant("B");
    }

    private Tenant tenant(String label) throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);

        Library library = new Library();
        library.setName("Resource Library " + label + " " + suffix);
        library = libraryRepository.save(library);

        User admin = persistUser(library, "dr-" + suffix + "-admin", Role.ROLE_ADMIN);
        User librarian = persistUser(library, "dr-" + suffix + "-librarian", Role.ROLE_LIBRARIAN);
        User member = persistUser(library, "dr-" + suffix + "-member", Role.ROLE_MEMBER);

        Book book = new Book();
        book.setTitle("Resource Book " + suffix);
        book.setAuthor("An Author");
        book.setIsbn("dr-" + label + "-" + suffix);
        book.setTotalCopies(3);
        book.setAvailableCopies(3);
        book.setLibrary(library);
        book = bookRepository.save(book);

        return new Tenant(library, book.getId(), login(admin), login(librarian), login(member));
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

    private String login(User user) throws Exception {
        String body = objectMapper.createObjectNode()
                .put("username", user.getUsername())
                .put("password", PASSWORD)
                .toString();
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();

        assertThat(status(result)).as("login for %s", user.getUsername()).isEqualTo(200);
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("token").asText();
    }

    private String requestJson(Tenant tenant, String title, String url, Boolean enabled) {
        var body = objectMapper.createObjectNode()
                .put("bookId", tenant.bookId())
                .put("title", title)
                .put("description", "A description")
                .put("resourceType", "PDF")
                .put("resourceUrl", url);
        if (enabled != null) {
            body.put("enabled", enabled);
        }
        return body.toString();
    }

    private MvcResult call(MockHttpServletRequestBuilder request, String token) throws Exception {
        if (token != null) {
            request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        return mockMvc.perform(request).andReturn();
    }

    private MvcResult create(Tenant tenant, String title, Boolean enabled, String token) throws Exception {
        return call(post("/api/digital-resources")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestJson(tenant, title, URL, enabled)), token);
    }

    /** Creates a resource and returns its id. */
    private long resourceOf(Tenant tenant, String title, boolean enabled) throws Exception {
        MvcResult result = create(tenant, title, enabled, tenant.librarianToken());

        assertThat(status(result)).as("create").isEqualTo(201);
        return json(result).path("id").asLong();
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private static int status(MvcResult result) {
        return result.getResponse().getStatus();
    }

    // ---------- nothing is public ----------

    @Test
    void anonymousCallersReachNothing() throws Exception {
        long id = resourceOf(a, "Chapter one", true);

        assertThat(status(call(get("/api/digital-resources"), null))).isEqualTo(401);
        assertThat(status(call(get("/api/digital-resources/{id}", id), null))).isEqualTo(401);
        assertThat(status(call(post("/api/digital-resources")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestJson(a, "Sneaky", URL, true)), null))).isEqualTo(401);
        assertThat(status(call(delete("/api/digital-resources/{id}", id), null))).isEqualTo(401);
    }

    // ---------- staff write, members do not ----------

    @Test
    void staffOfEitherKindCanCreateUpdateDisableAndDelete() throws Exception {
        MvcResult created = create(a, "Chapter one", null, a.adminToken());
        assertThat(status(created)).isEqualTo(201);
        long id = json(created).path("id").asLong();
        assertThat(json(created).path("enabled").asBoolean()).as("enabled unless told otherwise").isTrue();

        MvcResult updated = call(put("/api/digital-resources/{id}", id)
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestJson(a, "Chapter two", URL, null)), a.librarianToken());
        assertThat(status(updated)).isEqualTo(200);
        assertThat(json(updated).path("title").asText()).isEqualTo("Chapter two");

        MvcResult disabled = call(patch("/api/digital-resources/{id}/status", id)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":false}"), a.librarianToken());
        assertThat(status(disabled)).isEqualTo(200);
        assertThat(json(disabled).path("enabled").asBoolean()).isFalse();

        assertThat(status(call(delete("/api/digital-resources/{id}", id), a.adminToken()))).isEqualTo(204);
        assertThat(digitalResourceRepository.findById(id)).isEmpty();
    }

    @Test
    void aMemberIsRefusedEveryWrite() throws Exception {
        long id = resourceOf(a, "Chapter one", true);

        assertThat(status(create(a, "Sneaky", true, a.memberToken()))).isEqualTo(403);
        assertThat(status(call(put("/api/digital-resources/{id}", id)
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestJson(a, "Sneaky", URL, true)), a.memberToken()))).isEqualTo(403);
        assertThat(status(call(patch("/api/digital-resources/{id}/status", id)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":false}"), a.memberToken()))).isEqualTo(403);
        assertThat(status(call(delete("/api/digital-resources/{id}", id), a.memberToken()))).isEqualTo(403);

        assertThat(digitalResourceRepository.findById(id)).as("and nothing changed").isPresent();
    }

    // ---------- enabled decides what a member sees ----------

    @Test
    void aMemberSeesEnabledResourcesAndNotDisabledOnes() throws Exception {
        long enabled = resourceOf(a, "Readable", true);
        long disabled = resourceOf(a, "Withdrawn", false);

        MvcResult list = call(get("/api/digital-resources?size=50"), a.memberToken());
        assertThat(status(list)).isEqualTo(200);

        List<Long> ids = new ArrayList<>();
        for (JsonNode resource : json(list).path("content")) {
            ids.add(resource.path("id").asLong());
        }
        assertThat(ids).contains(enabled).doesNotContain(disabled);

        assertThat(status(call(get("/api/digital-resources/{id}", enabled), a.memberToken()))).isEqualTo(200);
        assertThat(status(call(get("/api/digital-resources/{id}", disabled), a.memberToken())))
                .as("a 404, so turning one off does not advertise that it exists")
                .isEqualTo(404);
    }

    @Test
    void staffSeeDisabledResourcesTheirMembersCannot() throws Exception {
        long disabled = resourceOf(a, "Withdrawn", false);

        assertThat(status(call(get("/api/digital-resources/{id}", disabled), a.librarianToken()))).isEqualTo(200);
        assertThat(status(call(get("/api/digital-resources/{id}", disabled), a.adminToken()))).isEqualTo(200);
    }

    @Test
    void aMemberCannotAskForDisabledOnesThroughTheList() throws Exception {
        long disabled = resourceOf(a, "Withdrawn", false);

        MvcResult list = call(get("/api/digital-resources?size=50&bookId=" + a.bookId()), a.memberToken());

        assertThat(json(list).path("content").toString())
                .as("the enabled filter is not a parameter a member can turn off")
                .doesNotContain("\"id\":" + disabled);
    }

    // ---------- one library only ----------

    @Test
    void aResourceOfAnotherLibraryIsNotFound() throws Exception {
        long resourceOfB = resourceOf(b, "B's chapter", true);

        assertThat(status(call(get("/api/digital-resources/{id}", resourceOfB), a.memberToken()))).isEqualTo(404);
        assertThat(status(call(get("/api/digital-resources/{id}", resourceOfB), a.adminToken()))).isEqualTo(404);
        assertThat(status(call(put("/api/digital-resources/{id}", resourceOfB)
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestJson(a, "Stolen", URL, true)), a.adminToken()))).isEqualTo(404);
        assertThat(status(call(delete("/api/digital-resources/{id}", resourceOfB), a.adminToken()))).isEqualTo(404);

        assertThat(digitalResourceRepository.findById(resourceOfB)).as("B's resource is untouched").isPresent();
    }

    @Test
    void anotherLibrarysResourcesAreNeverOnThePage() throws Exception {
        long resourceOfA = resourceOf(a, "A's chapter", true);
        long resourceOfB = resourceOf(b, "B's chapter", true);

        String pageOfA = call(get("/api/digital-resources?size=50"), a.adminToken())
                .getResponse().getContentAsString();

        assertThat(pageOfA).contains("\"id\":" + resourceOfA).doesNotContain("\"id\":" + resourceOfB);
    }

    @Test
    void aBookOfAnotherLibraryCannotBeAttachedTo() throws Exception {
        String body = objectMapper.createObjectNode()
                .put("bookId", b.bookId())
                .put("title", "Reaching across")
                .put("resourceType", "PDF")
                .put("resourceUrl", URL)
                .toString();

        MvcResult result = call(post("/api/digital-resources")
                .contentType(MediaType.APPLICATION_JSON).content(body), a.adminToken());

        assertThat(status(result)).as("the same 404 a book that never existed gets").isEqualTo(404);
    }

    // ---------- what may be sent ----------

    @Test
    void aMissingOrInvalidFieldIsRefused() throws Exception {
        assertThat(status(call(post("/api/digital-resources")
                .contentType(MediaType.APPLICATION_JSON).content("{}"), a.adminToken()))).isEqualTo(400);

        assertThat(status(call(post("/api/digital-resources")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestJson(a, "   ", URL, true)), a.adminToken())))
                .as("a blank title").isEqualTo(400);

        assertThat(status(call(post("/api/digital-resources")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestJson(a, "Too long", "x".repeat(2049), true)), a.adminToken())))
                .as("an over-long url").isEqualTo(400);
    }

    @Test
    void aUrlThatIsNotHttpOrHttpsIsRefused() throws Exception {
        for (String url : List.of("javascript:alert(1)", "data:text/html,<script>", "file:///etc/passwd",
                "ftp://files.example.invalid/book.pdf")) {
            MvcResult result = call(post("/api/digital-resources")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(requestJson(a, "Bad link", url, true)), a.adminToken());

            assertThat(status(result)).as(url).isEqualTo(400);
        }
    }

    @Test
    void anUnknownResourceTypeIsRefused() throws Exception {
        String body = objectMapper.createObjectNode()
                .put("bookId", a.bookId())
                .put("title", "Audio")
                .put("resourceType", "AUDIO")
                .put("resourceUrl", URL)
                .toString();

        assertThat(status(call(post("/api/digital-resources")
                .contentType(MediaType.APPLICATION_JSON).content(body), a.adminToken()))).isEqualTo(400);
    }

    // ---------- what comes back ----------

    @Test
    void aResponseCarriesTheResourceAndNotTheLibrary() throws Exception {
        long id = resourceOf(a, "Chapter one", true);

        JsonNode body = json(call(get("/api/digital-resources/{id}", id), a.memberToken()));

        List<String> fields = new ArrayList<>();
        body.fieldNames().forEachRemaining(fields::add);

        assertThat(fields).containsExactlyInAnyOrderElementsOf(RESPONSE_FIELDS);
        assertThat(body.path("bookId").asLong()).isEqualTo(a.bookId());
        assertThat(body.path("bookTitle").asText()).isNotBlank();
        assertThat(body.path("resourceUrl").asText()).isEqualTo(URL);
        assertThat(body.path("resourceType").asText()).isEqualTo("PDF");
    }

    @Test
    void theListIsPagedLikeEveryOtherListInTheApi() throws Exception {
        resourceOf(a, "One", true);
        resourceOf(a, "Two", true);

        MvcResult page = call(get("/api/digital-resources?page=0&size=1&sortBy=title&direction=asc"),
                a.adminToken());

        assertThat(status(page)).isEqualTo(200);
        assertThat(json(page).path("content")).hasSize(1);
        assertThat(json(page).path("size").asInt()).isEqualTo(1);

        assertThat(status(call(get("/api/digital-resources?size=51"), a.adminToken()))).isEqualTo(400);
        assertThat(status(call(get("/api/digital-resources?sortBy=resourceUrl"), a.adminToken()))).isEqualTo(400);
    }
}
