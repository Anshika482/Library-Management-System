package com.library.lms.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

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
import com.library.lms.entity.Category;
import com.library.lms.entity.Library;
import com.library.lms.entity.Role;
import com.library.lms.entity.User;
import com.library.lms.repository.BookRepository;
import com.library.lms.repository.CategoryRepository;
import com.library.lms.repository.LibraryRepository;
import com.library.lms.repository.UserRepository;

/**
 * Proves the three list endpoints that used to return everything at once - book
 * search, books by category name and the category list - now answer one
 * bounded page at a time, inside the caller's library.
 *
 * <p><b>Two libraries, deliberately alike.</b> Both have a shelf with the same
 * name, and every book in both carries the same keyword, so a query that lost
 * its library condition would visibly return the other library's rows and
 * inflate the totals. The tests walk every page, past the last one, and require
 * the pages together to hold each of the caller's rows exactly once, in
 * order.</p>
 *
 * <p><b>The limits are the book list's.</b> The same defaults, the same size
 * ceiling, and the same page and sort errors word for word, on all three
 * endpoints.</p>
 *
 * <p><b>Isolation:</b> the throwaway schema the other integration tests use,
 * never the development database, with fresh libraries and a unique suffix per
 * test.</p>
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:mysql://localhost:3306/library_db_step129_it"
                + "?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true"
                + "&serverTimezone=UTC",
        "spring.jpa.hibernate.ddl-auto=update",
        "spring.jpa.open-in-view=false"
})
@AutoConfigureMockMvc
class BookCategoryPaginationIntegrationTest {

    /** Test-only credential, never a real one, and never reused outside this class. */
    private static final String TEST_PASSWORD = "step153-test-only-password";

    /** How many books - and, separately, how many categories - the caller's library holds. */
    private static final int OWN = 12;

    /** Books in the other library, matching the same keyword and the same shelf name. */
    private static final int OTHER_BOOKS = 3;

    private static final String BOOK_SORT_FIELDS =
            "Unsupported sort field. Allowed fields are: author, availableCopies, id, isbn, title, totalCopies";

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
    private BookRepository bookRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private String suffix;
    private String shelfName;
    private final List<Long> ownBookIds = new ArrayList<>();
    private final List<Long> otherBookIds = new ArrayList<>();
    private final List<Long> ownCategoryIds = new ArrayList<>();
    private final List<Long> otherCategoryIds = new ArrayList<>();
    private String ownToken;
    private String otherToken;

    // ---------- fixtures ----------

    @BeforeEach
    void stockTwoLibraries() throws Exception {
        suffix = UUID.randomUUID().toString().substring(0, 8);
        shelfName = "Step153 Shelf " + suffix;

        Library own = library("Own");
        Library other = library("Other");

        // The same shelf name in both libraries.
        Category ownShelf = category(own, shelfName);
        Category otherShelf = category(other, shelfName);

        for (int n = 1; n <= OWN; n++) {
            ownBookIds.add(book(own, ownShelf, "a", n).getId());
        }
        for (int n = 1; n <= OTHER_BOOKS; n++) {
            otherBookIds.add(book(other, otherShelf, "b", n).getId());
        }

        ownCategoryIds.add(ownShelf.getId());
        for (int n = 1; n < OWN; n++) {
            ownCategoryIds.add(category(own, String.format("Step153 Category %s %02d", suffix, n)).getId());
        }
        otherCategoryIds.add(otherShelf.getId());
        otherCategoryIds.add(category(other, "Step153 Category " + suffix + " other").getId());

        ownToken = login(member(own, "own"));
        otherToken = login(member(other, "other"));
    }

    private Library library(String label) {
        Library library = new Library();
        library.setName("Step153 " + label + " Library " + suffix);
        return libraryRepository.save(library);
    }

    private Category category(Library library, String name) {
        Category category = new Category();
        category.setName(name);
        category.setLibrary(library);
        return categoryRepository.save(category);
    }

    /** Every title and ISBN carries the suffix, which is why it finds them all. */
    private Book book(Library library, Category shelf, String tag, int n) {
        Book book = new Book();
        book.setTitle(String.format("Step153 %s %s Book %02d", suffix, tag, n));
        book.setAuthor("Step153 Author");
        book.setIsbn(String.format("153-%s-%s%02d", suffix, tag, n));
        book.setTotalCopies(1);
        book.setAvailableCopies(1);
        book.setCategory(shelf);
        book.setLibrary(library);
        return bookRepository.save(book);
    }

    private User member(Library library, String label) {
        String username = "step153-" + label + "-" + suffix;

        User user = new User();
        user.setUsername(username);
        user.setEmail(username + "@example.invalid");
        user.setPassword(passwordEncoder.encode(TEST_PASSWORD));
        user.setFullName("Step153 " + label);
        user.setRole(Role.ROLE_MEMBER);
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

    private MvcResult perform(MockHttpServletRequestBuilder request, String token) throws Exception {
        return mockMvc.perform(request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)).andReturn();
    }

    /** A 200 page, as JSON. */
    private JsonNode page(MockHttpServletRequestBuilder request, String token) throws Exception {
        MvcResult result = perform(request, token);

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private static List<Long> ids(JsonNode page) {
        List<Long> ids = new ArrayList<>();
        page.path("content").forEach(item -> ids.add(item.path("id").asLong()));
        return ids;
    }

    private static void assertPage(JsonNode page, int number, int size, long totalElements, int totalPages) {
        assertThat(page.path("page").asInt()).as("page").isEqualTo(number);
        assertThat(page.path("size").asInt()).as("size").isEqualTo(size);
        assertThat(page.path("totalElements").asLong()).as("totalElements").isEqualTo(totalElements);
        assertThat(page.path("totalPages").asInt()).as("totalPages").isEqualTo(totalPages);
    }

    /**
     * Reads pages of five, one past the last, checking the totals and the size
     * of each, and returns every id seen in the order the pages gave them.
     */
    private List<Long> walkPagesOfFive(Supplier<MockHttpServletRequestBuilder> endpoint, long total, String token)
            throws Exception {
        int totalPages = (int) Math.ceil(total / 5.0);
        List<Long> seen = new ArrayList<>();

        for (int number = 0; number <= totalPages; number++) {
            JsonNode page = page(endpoint.get().param("page", String.valueOf(number)).param("size", "5"), token);
            assertPage(page, number, 5, total, totalPages);

            long expected = number < totalPages ? Math.min(5, total - 5L * number) : 0;
            assertThat(ids(page)).as("page %d", number).hasSize((int) expected);
            seen.addAll(ids(page));
        }

        return seen;
    }

    private void assertError(String label, MvcResult result, int expectedStatus, String expectedMessage)
            throws Exception {
        assertThat(result.getResponse().getStatus()).as(label).isEqualTo(expectedStatus);

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.path("status").asInt()).as(label).isEqualTo(expectedStatus);
        assertThat(body.path("message").asText()).as(label).isEqualTo(expectedMessage);
    }

    /** The three endpoints this step paginated, each already valid apart from paging. */
    private Map<String, Supplier<MockHttpServletRequestBuilder>> endpoints() {
        return Map.of(
                "book search", () -> get("/api/books/search").param("keyword", suffix),
                "books by category", () -> get("/api/books/category/{name}", shelfName),
                "category list", () -> get("/api/categories"));
    }

    // ---------- book search ----------

    @Test
    void searchIsServedOnePageAtATimeWithTheRealTotals() throws Exception {
        List<Long> mine = walkPagesOfFive(() -> get("/api/books/search").param("keyword", suffix), OWN, ownToken);

        assertThat(mine)
                .as("every one of the caller's books exactly once, in id order, and nobody else's")
                .containsExactlyElementsOf(ownBookIds);

        // The other library searches for the same keyword and sees only its own.
        List<Long> theirs =
                walkPagesOfFive(() -> get("/api/books/search").param("keyword", suffix), OTHER_BOOKS, otherToken);
        assertThat(theirs).containsExactlyElementsOf(otherBookIds);
    }

    @Test
    void searchTakesTheBookListsDefaults() throws Exception {
        JsonNode first = page(get("/api/books/search").param("keyword", suffix), ownToken);

        assertPage(first, 0, 10, OWN, 2);
        assertThat(ids(first)).containsExactlyElementsOf(ownBookIds.subList(0, 10));
    }

    @Test
    void aBlankKeywordIsBoundedAndStaysInsideTheCallersLibrary() throws Exception {
        for (String blank : List.of("", "   ")) {
            // No filter, as before - but a page, not the whole library at once.
            JsonNode first = page(get("/api/books/search").param("keyword", blank), ownToken);
            assertPage(first, 0, 10, OWN, 2);
            assertThat(ids(first)).as("'%s' first page", blank).hasSize(10);

            List<Long> all = walkPagesOfFive(() -> get("/api/books/search").param("keyword", blank), OWN, ownToken);
            assertThat(all).as("'%s' every page", blank).containsExactlyElementsOf(ownBookIds);
        }

        List<Long> theirs =
                walkPagesOfFive(() -> get("/api/books/search").param("keyword", " "), OTHER_BOOKS, otherToken);
        assertThat(theirs).containsExactlyElementsOf(otherBookIds);

        // Leaving the keyword out altogether is still a malformed request.
        assertError("missing keyword", perform(get("/api/books/search"), ownToken), 400,
                "Required request parameter is missing: keyword");
    }

    // ---------- books by category name ----------

    @Test
    void booksByCategoryArePagedAndMatchedOnlyInsideTheCallersLibrary() throws Exception {
        List<Long> mine = walkPagesOfFive(() -> get("/api/books/category/{name}", shelfName), OWN, ownToken);
        assertThat(mine).containsExactlyElementsOf(ownBookIds);

        // The same shelf name in the other library holds only its three books.
        List<Long> theirs =
                walkPagesOfFive(() -> get("/api/books/category/{name}", shelfName), OTHER_BOOKS, otherToken);
        assertThat(theirs).containsExactlyElementsOf(otherBookIds);

        assertPage(page(get("/api/books/category/{name}", shelfName), ownToken), 0, 10, OWN, 2);
        assertPage(page(get("/api/books/category/{name}", "Step153 No Such Shelf " + suffix), ownToken),
                0, 10, 0, 0);

        // The book list's sort fields apply. Titles are zero-padded, so
        // descending title is exactly descending creation order.
        JsonNode byTitle = page(get("/api/books/category/{name}", shelfName)
                .param("sortBy", "title").param("direction", "DESC").param("size", "50"), ownToken);
        List<Long> reversed = new ArrayList<>(ownBookIds);
        Collections.reverse(reversed);
        assertThat(ids(byTitle)).containsExactlyElementsOf(reversed);
    }

    // ---------- the category list ----------

    @Test
    void categoriesArePagedAndScopedToTheCallersLibrary() throws Exception {
        List<Long> mine = walkPagesOfFive(() -> get("/api/categories"), OWN, ownToken);
        assertThat(mine).as("every category once, in id order").containsExactlyElementsOf(ownCategoryIds);

        List<Long> theirs = walkPagesOfFive(() -> get("/api/categories"), otherCategoryIds.size(), otherToken);
        assertThat(theirs).containsExactlyElementsOf(otherCategoryIds);

        JsonNode first = page(get("/api/categories"), ownToken);
        assertPage(first, 0, 10, OWN, 2);
        assertThat(ids(first))
                .as("the first ten by id, the order this list always had")
                .containsExactlyElementsOf(ownCategoryIds.subList(0, 10));
    }

    @Test
    void categoriesCanBeSortedByName() throws Exception {
        JsonNode byName = page(get("/api/categories")
                .param("sortBy", "name").param("direction", "desc").param("size", "50"), ownToken);

        List<String> names = new ArrayList<>();
        byName.path("content").forEach(item -> names.add(item.path("name").asText()));

        List<String> descending = new ArrayList<>(names);
        descending.sort(Collections.reverseOrder());
        assertThat(names).hasSize(OWN).containsExactlyElementsOf(descending);
    }

    // ---------- limits ----------

    @Test
    void unreasonablePagesAndSizesAreRefusedOnEveryEndpoint() throws Exception {
        for (Map.Entry<String, Supplier<MockHttpServletRequestBuilder>> endpoint : endpoints().entrySet()) {
            String label = endpoint.getKey();
            Supplier<MockHttpServletRequestBuilder> request = endpoint.getValue();

            assertError(label + " page=-1", perform(request.get().param("page", "-1"), ownToken), 400,
                    "Page must be 0 or greater, but was -1");
            assertError(label + " size=0", perform(request.get().param("size", "0"), ownToken), 400,
                    "Size must be at least 1, but was 0");
            assertError(label + " size=51", perform(request.get().param("size", "51"), ownToken), 400,
                    "Size must not exceed 50, but was 51");
            assertError(label + " size=1000000", perform(request.get().param("size", "1000000"), ownToken), 400,
                    "Size must not exceed 50, but was 1000000");
            assertError(label + " size=all", perform(request.get().param("size", "all"), ownToken), 400,
                    "Invalid value for 'size'");

            // The ceiling itself is allowed, and holds everything here.
            assertPage(page(request.get().param("size", "50"), ownToken), 0, 50, OWN, 1);
        }
    }

    @Test
    void sortingIsLimitedToTheAllowedFieldsOnEveryEndpoint() throws Exception {
        assertError("book search", perform(get("/api/books/search")
                .param("keyword", suffix).param("sortBy", "password"), ownToken), 400, BOOK_SORT_FIELDS);
        assertError("books by category", perform(get("/api/books/category/{name}", shelfName)
                .param("sortBy", "library.id"), ownToken), 400, BOOK_SORT_FIELDS);
        assertError("category list", perform(get("/api/categories")
                .param("sortBy", "library"), ownToken), 400, "Unsupported sort field. Allowed fields are: id, name");

        for (Map.Entry<String, Supplier<MockHttpServletRequestBuilder>> endpoint : endpoints().entrySet()) {
            assertError(endpoint.getKey() + " direction", perform(endpoint.getValue().get()
                    .param("direction", "sideways"), ownToken), 400,
                    "Unsupported sort direction. Allowed directions are: asc, desc");
        }
    }
}
