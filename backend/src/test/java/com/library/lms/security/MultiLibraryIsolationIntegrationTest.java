package com.library.lms.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.time.LocalDate;
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
import com.library.lms.entity.Category;
import com.library.lms.entity.Library;
import com.library.lms.entity.Role;
import com.library.lms.entity.Transaction;
import com.library.lms.entity.TransactionStatus;
import com.library.lms.entity.User;
import com.library.lms.repository.BookRepository;
import com.library.lms.repository.CategoryRepository;
import com.library.lms.repository.LibraryRepository;
import com.library.lms.repository.TransactionRepository;
import com.library.lms.repository.UserRepository;

/**
 * Proves, end to end, that two libraries sharing one database cannot see or
 * touch each other's books, categories or loans.
 *
 * <p>The service-level scoping tests use mocked repositories: they prove each
 * service <i>asks</i> for library-scoped rows, not that the derived queries
 * actually filter by library when they reach MySQL. Every other integration
 * test builds a single library, where a missing filter is invisible because
 * there is nothing else to leak. This class builds two, fills both with real
 * rows, logs in real users of each, and drives the real filter chain,
 * controllers, services, repositories and database.</p>
 *
 * <p><b>The fixture is built to make a leak visible.</b> Both libraries hold a
 * category with the <i>same name</i> and a book with the <i>same ISBN</i>, so
 * any search, filter or lookup that forgot its library condition would return
 * two rows instead of one. Each library also has an issued loan belonging to
 * its member, stored directly because the API issues books only to the staff
 * member performing the action.</p>
 *
 * <p><b>Every check runs in both directions</b> - A against B's rows, then B
 * against A's - and for every role that applies, <b>ADMIN included</b>. ADMIN
 * is the most privileged role in a library, not a role above libraries, and the
 * tests hold it to the same boundary.</p>
 *
 * <p><b>A record from the other library must be indistinguishable from one
 * that does not exist.</b> A 403 or 409 where a 404 belongs would confirm the
 * id is real. The tests assert the same status and message shape in both
 * cases, and after every refused write they read the other library's row back
 * from the database to prove nothing changed.</p>
 *
 * <p><b>Isolation:</b> the properties below are identical to
 * {@code SecurityHttpIntegrationTest}'s, so both classes share one isolated
 * schema and one cached Spring context, never the development database. Each
 * test builds two brand-new libraries with unique names, so exact counts such
 * as "this library holds exactly one book" hold regardless of other rows in the
 * schema.</p>
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:mysql://localhost:3306/library_db_step129_it"
                + "?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true"
                + "&serverTimezone=UTC",
        "spring.jpa.hibernate.ddl-auto=update",
        "spring.jpa.open-in-view=false"
})
@AutoConfigureMockMvc
class MultiLibraryIsolationIntegrationTest {

    /** Test-only credential, never a real one, and never reused outside this class. */
    private static final String TEST_PASSWORD = "step142-test-only-password";

    /** An id no row will reach, for the "does not exist" comparison. */
    private static final long MISSING_ID = Long.MAX_VALUE - 1;

    /** BCrypt is deliberately slow; hashed once and reused by every fixture user. */
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
    private CategoryRepository categoryRepository;

    @Autowired
    private BookRepository bookRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    /** One library's worth of real rows and real tokens. */
    private record Tenant(String label, Library library,
                          User admin, User librarian, User member,
                          String adminToken, String librarianToken, String memberToken,
                          Category category, Book book, Transaction memberLoan) {

        List<String> staffTokens() {
            return List.of(adminToken, librarianToken);
        }

        List<String> allTokens() {
            return List.of(adminToken, librarianToken, memberToken);
        }
    }

    /** One caller's library and the library it must not reach. */
    private record Direction(Tenant self, Tenant other) {

        String label() {
            return self.label() + "->" + other.label();
        }
    }

    private String suffix;
    private String sharedIsbn;
    private String sharedCategoryName;
    private Tenant a;
    private Tenant b;

    // ---------- fixtures ----------

    @BeforeEach
    void createTwoLibraries() throws Exception {
        suffix = UUID.randomUUID().toString().substring(0, 8);
        sharedIsbn = "ISO-" + suffix;
        sharedCategoryName = "Shared " + suffix;

        a = tenant("A");
        b = tenant("B");
    }

    private Tenant tenant(String label) throws Exception {
        Library library = new Library();
        library.setName("Step142 Library " + label + " " + suffix);
        library = libraryRepository.save(library);

        User admin = persistUser(library, Role.ROLE_ADMIN, label);
        User librarian = persistUser(library, Role.ROLE_LIBRARIAN, label);
        User member = persistUser(library, Role.ROLE_MEMBER, label);

        Category category = new Category();
        category.setName(sharedCategoryName);
        category.setLibrary(library);
        category = categoryRepository.save(category);

        Book book = new Book();
        book.setTitle("Step142 Book " + label);
        book.setAuthor("Author " + label);
        book.setIsbn(sharedIsbn);
        book.setCategory(category);
        book.setLibrary(library);
        book.setTotalCopies(2);
        book.setAvailableCopies(1);
        book = bookRepository.save(book);

        Transaction loan = new Transaction();
        loan.setBook(book);
        loan.setUser(member);
        loan.setLibrary(library);
        loan.setIssueDate(LocalDate.now());
        loan.setDueDate(LocalDate.now().plusDays(14));
        loan.setStatus(TransactionStatus.ISSUED);
        loan = transactionRepository.save(loan);

        return new Tenant(label, library, admin, librarian, member,
                login(admin), login(librarian), login(member),
                category, book, loan);
    }

    private User persistUser(Library library, Role role, String label) {
        if (encodedPassword == null) {
            encodedPassword = passwordEncoder.encode(TEST_PASSWORD);
        }
        String username = "step142-" + label.toLowerCase() + "-"
                + role.name().toLowerCase().replace("role_", "") + "-" + suffix;

        User user = new User();
        user.setUsername(username);
        user.setEmail(username + "@example.invalid");
        user.setPassword(encodedPassword);
        user.setFullName("Step142 " + label + " " + role.name());
        user.setRole(role);
        user.setLibrary(library);
        return userRepository.save(user);
    }

    /** Logs in for real and returns the issued token. */
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
        return json(result).path("token").asText();
    }

    private List<Direction> bothDirections() {
        return List.of(new Direction(a, b), new Direction(b, a));
    }

    // ---------- helpers ----------

    private MvcResult call(MockHttpServletRequestBuilder request, String token) throws Exception {
        return mockMvc.perform(request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)).andReturn();
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private static int status(MvcResult result) {
        return result.getResponse().getStatus();
    }

    private String message(MvcResult result) throws Exception {
        return json(result).path("message").asText();
    }

    /** The ids in a JSON array, or in the {@code content} of a paged response. */
    private List<Long> ids(MvcResult result) throws Exception {
        JsonNode node = json(result);
        JsonNode items = node.isArray() ? node : node.path("content");
        List<Long> ids = new ArrayList<>();
        items.forEach(item -> ids.add(item.path("id").asLong()));
        return ids;
    }

    private String bookJson(String isbn, Long categoryId) {
        return objectMapper.createObjectNode()
                .put("title", "Written by the other library")
                .put("author", "Intruder")
                .put("isbn", isbn)
                .put("totalCopies", 2)
                .put("categoryId", categoryId)
                .toString();
    }

    private String categoryJson(String name) {
        return objectMapper.createObjectNode().put("name", name).toString();
    }

    private String issueJson(Long bookId) {
        return objectMapper.createObjectNode()
                .put("bookId", bookId)
                .put("dueDate", LocalDate.now().plusDays(7).toString())
                .toString();
    }

    /** Asserts a 404 whose message has the same shape a genuinely missing id produces. */
    private void assertLooksMissing(String label, MvcResult result, String messagePrefix, long id)
            throws Exception {
        assertThat(status(result)).as("%s status", label).isEqualTo(404);
        assertThat(message(result)).as("%s message", label).isEqualTo(messagePrefix + id);
    }

    // ---------- books ----------

    @Test
    void eachLibrarySeesOnlyItsOwnBooks() throws Exception {
        for (Direction d : bothDirections()) {
            for (String token : d.self().allTokens()) {
                assertThat(ids(call(get("/api/books"), token)))
                        .as("%s book list", d.label())
                        .containsExactly(d.self().book().getId());
            }
        }
    }

    @Test
    void aBookFromTheOtherLibraryLooksExactlyLikeABookThatDoesNotExist() throws Exception {
        for (Direction d : bothDirections()) {
            for (String token : d.self().allTokens()) {
                Long theirs = d.other().book().getId();
                assertLooksMissing(d.label() + " GET other library's book",
                        call(get("/api/books/{id}", theirs), token), "Book not found with id: ", theirs);
                assertLooksMissing(d.label() + " GET missing book",
                        call(get("/api/books/{id}", MISSING_ID), token), "Book not found with id: ", MISSING_ID);
            }
        }
    }

    @Test
    void searchAndFiltersNeverReachAcrossLibraries() throws Exception {
        // Same ISBN and same category name in both libraries: a filter that
        // lost its library condition would return two books here, not one.
        for (Direction d : bothDirections()) {
            String token = d.self().memberToken();
            Long mine = d.self().book().getId();

            assertThat(ids(call(get("/api/books").param("keyword", sharedIsbn), token)))
                    .as("%s paged keyword filter", d.label()).containsExactly(mine);
            assertThat(ids(call(get("/api/books/search").param("keyword", sharedIsbn), token)))
                    .as("%s search endpoint", d.label()).containsExactly(mine);
            assertThat(ids(call(get("/api/books/category/{name}", sharedCategoryName), token)))
                    .as("%s by category name", d.label()).containsExactly(mine);
            assertThat(ids(call(get("/api/books").param("categoryId",
                    String.valueOf(d.self().category().getId())), token)))
                    .as("%s filtered by own category id", d.label()).containsExactly(mine);

            Long theirCategory = d.other().category().getId();
            assertLooksMissing(d.label() + " filtered by the other library's category id",
                    call(get("/api/books").param("categoryId", String.valueOf(theirCategory)), token),
                    "Category not found with id: ", theirCategory);
        }
    }

    @Test
    void staffCannotChangeOrDeleteTheOtherLibrarysBooks() throws Exception {
        for (Direction d : bothDirections()) {
            Book theirs = d.other().book();
            for (String token : d.self().staffTokens()) {
                assertLooksMissing(d.label() + " PUT other library's book",
                        call(put("/api/books/{id}", theirs.getId())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(bookJson("HIJACK-" + suffix, null)), token),
                        "Book not found with id: ", theirs.getId());
                assertLooksMissing(d.label() + " DELETE other library's book",
                        call(delete("/api/books/{id}", theirs.getId()), token),
                        "Book not found with id: ", theirs.getId());
            }

            Book stored = bookRepository.findById(theirs.getId()).orElseThrow();
            assertThat(stored.getTitle()).as("%s other book untouched", d.label()).isEqualTo(theirs.getTitle());
            assertThat(stored.getIsbn()).isEqualTo(sharedIsbn);
            assertThat(stored.getTotalCopies()).isEqualTo(2);
        }
    }

    @Test
    void aBookCannotBeLinkedToTheOtherLibrarysCategory() throws Exception {
        for (Direction d : bothDirections()) {
            String token = d.self().librarianToken();
            Long theirCategory = d.other().category().getId();
            String newIsbn = "CROSS" + d.self().label() + "-" + suffix;

            assertLooksMissing(d.label() + " create a book in the other library's category",
                    call(post("/api/books").contentType(MediaType.APPLICATION_JSON)
                            .content(bookJson(newIsbn, theirCategory)), token),
                    "Category not found with id: ", theirCategory);
            assertThat(bookRepository.findByIsbnAndLibraryId(newIsbn, d.self().library().getId()))
                    .as("%s nothing was created", d.label()).isEmpty();

            assertLooksMissing(d.label() + " move own book into the other library's category",
                    call(put("/api/books/{id}", d.self().book().getId())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(bookJson(sharedIsbn, theirCategory)), token),
                    "Category not found with id: ", theirCategory);

            Book mine = bookRepository.findByIdAndLibraryId(d.self().book().getId(), d.self().library().getId())
                    .orElseThrow();
            assertThat(mine.getCategory().getId())
                    .as("%s own book still in its own category", d.label())
                    .isEqualTo(d.self().category().getId());
            assertThat(mine.getCategory().getLibrary().getId())
                    .as("%s and that category is in its own library", d.label())
                    .isEqualTo(d.self().library().getId());
        }
    }

    @Test
    void theSameIsbnAndCategoryNameCanExistInBothLibrariesButNotTwiceInOne() throws Exception {
        // The fixture already stores one shared ISBN and one shared category
        // name in both libraries directly. This proves the API allows it too,
        // and that uniqueness is still enforced inside a single library.
        String isbn = "NEW-" + suffix;
        String categoryName = "Fresh " + suffix;

        assertThat(status(call(post("/api/books").contentType(MediaType.APPLICATION_JSON)
                .content(bookJson(isbn, null)), a.librarianToken()))).as("ISBN in A").isEqualTo(201);
        assertThat(status(call(post("/api/books").contentType(MediaType.APPLICATION_JSON)
                .content(bookJson(isbn, null)), b.librarianToken()))).as("same ISBN in B").isEqualTo(201);
        assertThat(status(call(post("/api/books").contentType(MediaType.APPLICATION_JSON)
                .content(bookJson(isbn, null)), a.librarianToken()))).as("same ISBN twice in A").isEqualTo(400);

        assertThat(status(call(post("/api/categories").contentType(MediaType.APPLICATION_JSON)
                .content(categoryJson(categoryName)), a.librarianToken()))).as("category in A").isEqualTo(201);
        assertThat(status(call(post("/api/categories").contentType(MediaType.APPLICATION_JSON)
                .content(categoryJson(categoryName)), b.librarianToken()))).as("same name in B").isEqualTo(201);
        assertThat(status(call(post("/api/categories").contentType(MediaType.APPLICATION_JSON)
                .content(categoryJson(categoryName)), a.librarianToken()))).as("same name twice in A").isEqualTo(400);

        assertThat(bookRepository.findByIsbnAndLibraryId(isbn, a.library().getId())).isPresent();
        assertThat(bookRepository.findByIsbnAndLibraryId(isbn, b.library().getId())).isPresent();
    }

    // ---------- categories ----------

    @Test
    void eachLibrarySeesOnlyItsOwnCategories() throws Exception {
        for (Direction d : bothDirections()) {
            for (String token : d.self().allTokens()) {
                assertThat(ids(call(get("/api/categories"), token)))
                        .as("%s category list", d.label())
                        .containsExactly(d.self().category().getId());
            }
        }
    }

    @Test
    void staffCannotRenameOrDeleteTheOtherLibrarysCategory() throws Exception {
        for (Direction d : bothDirections()) {
            Long theirs = d.other().category().getId();
            for (String token : d.self().staffTokens()) {
                assertLooksMissing(d.label() + " PUT other library's category",
                        call(put("/api/categories/{id}", theirs).contentType(MediaType.APPLICATION_JSON)
                                .content(categoryJson("Hijacked " + suffix)), token),
                        "Category not found with id: ", theirs);
                // 404, not 409: the other category IS in use, and "in use" would
                // confirm that it exists.
                assertLooksMissing(d.label() + " DELETE other library's category",
                        call(delete("/api/categories/{id}", theirs), token),
                        "Category not found with id: ", theirs);
            }

            Category stored = categoryRepository.findById(theirs).orElseThrow();
            assertThat(stored.getName()).as("%s other category untouched", d.label()).isEqualTo(sharedCategoryName);
        }
    }

    // ---------- transactions ----------

    @Test
    void staffSeeOnlyTheirOwnLibrarysLoans() throws Exception {
        for (Direction d : bothDirections()) {
            for (String token : d.self().staffTokens()) {
                assertThat(ids(call(get("/api/transactions/status/ISSUED"), token)))
                        .as("%s issued loans", d.label())
                        .containsExactly(d.self().memberLoan().getId());
                assertThat(ids(call(get("/api/transactions/book/{id}", d.self().book().getId()), token)))
                        .as("%s own book history", d.label())
                        .containsExactly(d.self().memberLoan().getId());

                MvcResult theirHistory = call(get("/api/transactions/book/{id}", d.other().book().getId()), token);
                assertThat(status(theirHistory)).isEqualTo(200);
                assertThat(json(theirHistory).path("totalElements").asLong())
                        .as("%s other library's book history", d.label()).isZero();

                MvcResult theirMember = call(get("/api/transactions/user/{id}", d.other().member().getId()), token);
                assertThat(status(theirMember)).isEqualTo(200);
                assertThat(json(theirMember).path("totalElements").asLong())
                        .as("%s other library's member history", d.label()).isZero();

                Long theirLoan = d.other().memberLoan().getId();
                assertLooksMissing(d.label() + " GET other library's loan",
                        call(get("/api/transactions/{id}", theirLoan), token),
                        "Transaction not found with id: ", theirLoan);
            }
        }
    }

    @Test
    void aMemberSeesOnlyTheirOwnLoans() throws Exception {
        for (Direction d : bothDirections()) {
            String token = d.self().memberToken();
            Long myLoan = d.self().memberLoan().getId();

            assertThat(ids(call(get("/api/transactions/user/{id}", d.self().member().getId()), token)))
                    .as("%s own history", d.label()).containsExactly(myLoan);
            MvcResult own = call(get("/api/transactions/{id}", myLoan), token);
            assertThat(status(own)).isEqualTo(200);
            assertThat(json(own).path("id").asLong()).isEqualTo(myLoan);

            // Refused as "not yours" - identical for a loan in the other
            // library and for one that does not exist at all.
            for (MvcResult refused : List.of(
                    call(get("/api/transactions/user/{id}", d.other().member().getId()), token),
                    call(get("/api/transactions/{id}", d.other().memberLoan().getId()), token),
                    call(get("/api/transactions/{id}", MISSING_ID), token))) {
                assertThat(status(refused)).as("%s refusal status", d.label()).isEqualTo(403);
                assertThat(message(refused)).as("%s refusal message", d.label()).isEqualTo("Access denied");
            }
        }
    }

    @Test
    void staffCannotIssueOrReturnAcrossLibraries() throws Exception {
        for (Direction d : bothDirections()) {
            String token = d.self().librarianToken();
            Book theirBook = d.other().book();
            Transaction theirLoan = d.other().memberLoan();

            assertLooksMissing(d.label() + " issue the other library's book",
                    call(post("/api/transactions/issue").contentType(MediaType.APPLICATION_JSON)
                            .content(issueJson(theirBook.getId())), token),
                    "Book not found with id: ", theirBook.getId());
            assertLooksMissing(d.label() + " return the other library's loan",
                    call(post("/api/transactions/{id}/return", theirLoan.getId()), token),
                    "Transaction not found with id: ", theirLoan.getId());

            assertThat(bookRepository.findById(theirBook.getId()).orElseThrow().getAvailableCopies())
                    .as("%s other book's stock untouched", d.label()).isEqualTo(1);
            assertThat(transactionRepository.findById(theirLoan.getId()).orElseThrow().getStatus())
                    .as("%s other loan still issued", d.label()).isEqualTo(TransactionStatus.ISSUED);
            assertThat(ids(call(get("/api/transactions/book/{id}", theirBook.getId()), d.other().librarianToken())))
                    .as("%s no loan was created on the other book", d.label())
                    .containsExactly(theirLoan.getId());
        }
    }

    @Test
    void issuingAndReturningKeepsEveryRelationshipInsideOneLibrary() throws Exception {
        for (Direction d : bothDirections()) {
            String token = d.self().librarianToken();
            Long bookId = d.self().book().getId();

            MvcResult issued = call(post("/api/transactions/issue").contentType(MediaType.APPLICATION_JSON)
                    .content(issueJson(bookId)), token);
            assertThat(status(issued)).as("%s issue own book", d.label()).isEqualTo(201);
            Long loanId = json(issued).path("id").asLong();

            Transaction stored = transactionRepository.findById(loanId).orElseThrow();
            assertThat(stored.getLibrary().getId()).as("%s loan's library", d.label())
                    .isEqualTo(d.self().library().getId());
            assertThat(stored.getBook().getId()).as("%s loan's book", d.label()).isEqualTo(bookId);
            assertThat(stored.getUser().getId())
                    .as("%s loan's user (the API issues to the acting staff member)", d.label())
                    .isEqualTo(d.self().librarian().getId());
            assertThat(bookRepository.findById(bookId).orElseThrow().getAvailableCopies()).isZero();

            assertLooksMissing(d.label() + " other library reads the new loan",
                    call(get("/api/transactions/{id}", loanId), d.other().librarianToken()),
                    "Transaction not found with id: ", loanId);
            assertThat(ids(call(get("/api/transactions/status/ISSUED"), d.other().librarianToken())))
                    .as("%s other library's issued list", d.label()).doesNotContain(loanId);

            MvcResult returned = call(post("/api/transactions/{id}/return", loanId), token);
            assertThat(status(returned)).as("%s return own loan", d.label()).isEqualTo(200);
            assertThat(json(returned).path("status").asText()).isEqualTo("RETURNED");
            assertThat(bookRepository.findById(bookId).orElseThrow().getAvailableCopies()).isEqualTo(1);
        }
    }
}
