package com.library.lms.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.LocalDate;
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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.library.lms.entity.Book;
import com.library.lms.entity.Library;
import com.library.lms.entity.Role;
import com.library.lms.entity.Transaction;
import com.library.lms.entity.TransactionStatus;
import com.library.lms.entity.User;
import com.library.lms.repository.BookRepository;
import com.library.lms.repository.LibraryRepository;
import com.library.lms.repository.TransactionRepository;
import com.library.lms.repository.UserRepository;

/**
 * Proves a member of staff can lend a book to a member, and that the loan
 * records the member rather than the member of staff.
 *
 * <p>Until now {@code POST /api/transactions/issue} took a book and a due date
 * and recorded the <i>caller</i> as the borrower. The endpoint is staff-only,
 * so the only loan the system could record was a librarian borrowing from
 * themselves, and every "this member's loans" feature was unreachable.</p>
 *
 * <p><b>Who may be lent to is checked against the database, never taken from
 * the request.</b> An id from another library is refused exactly as an id
 * belonging to nobody is - a 404 with the same message - so the endpoint
 * cannot be used to find out which ids exist elsewhere. Staff accounts,
 * disabled accounts and locked accounts are all refused with one identical 400,
 * so it cannot be used to read an account's status either.</p>
 *
 * <p>Every refusal is followed by a read of the book row: a rejected loan must
 * leave the stock exactly where it was.</p>
 *
 * <p><b>Isolation:</b> the throwaway schema the other integration tests use,
 * never the development database, with a unique suffix per test.</p>
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:mysql://localhost:3306/library_db_step129_it"
                + "?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true"
                + "&serverTimezone=UTC",
        "spring.jpa.hibernate.ddl-auto=update",
        "spring.jpa.open-in-view=false"
})
@AutoConfigureMockMvc
class IssueBookToMemberIntegrationTest {

    /** Test-only credential, never a real one, and never reused outside this class. */
    private static final String TEST_PASSWORD = "step148-test-only-password";

    private static final int TOTAL_COPIES = 2;

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
    private TransactionRepository transactionRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private String suffix;
    private Library libraryA;
    private User librarian;
    private User admin;
    private User member;
    private User disabledMember;
    private User lockedMember;
    private User otherLibraryMember;
    private Book book;
    private String librarianToken;
    private String adminToken;

    // ---------- fixtures ----------

    @BeforeEach
    void createLibrariesAndUsers() throws Exception {
        suffix = UUID.randomUUID().toString().substring(0, 8);

        libraryA = newLibrary("A");
        admin = persistUser(libraryA, Role.ROLE_ADMIN, "admin", true, true);
        librarian = persistUser(libraryA, Role.ROLE_LIBRARIAN, "librarian", true, true);
        member = persistUser(libraryA, Role.ROLE_MEMBER, "member", true, true);
        disabledMember = persistUser(libraryA, Role.ROLE_MEMBER, "disabled", false, true);
        lockedMember = persistUser(libraryA, Role.ROLE_MEMBER, "locked", true, false);

        otherLibraryMember = persistUser(newLibrary("B"), Role.ROLE_MEMBER, "other", true, true);

        book = new Book();
        book.setTitle("Step148 Book");
        book.setAuthor("Author");
        book.setIsbn("STEP148-" + suffix);
        book.setLibrary(libraryA);
        book.setTotalCopies(TOTAL_COPIES);
        book.setAvailableCopies(TOTAL_COPIES);
        book = bookRepository.save(book);

        librarianToken = login(librarian);
        adminToken = login(admin);
    }

    private Library newLibrary(String label) {
        Library library = new Library();
        library.setName("Step148 Library " + label + " " + suffix);
        return libraryRepository.save(library);
    }

    private User persistUser(Library library, Role role, String label, boolean enabled, boolean unlocked) {
        String username = "step148-" + label + "-" + suffix;

        User user = new User();
        user.setUsername(username);
        user.setEmail(username + "@example.invalid");
        user.setPassword(passwordEncoder.encode(TEST_PASSWORD));
        user.setFullName("Step148 " + label);
        user.setRole(role);
        user.setLibrary(library);
        user.setEnabled(enabled);
        user.setAccountNonLocked(unlocked);
        return userRepository.save(user);
    }

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

    // ---------- helpers ----------

    private MvcResult issue(Long bookId, Long memberId, String token) throws Exception {
        String body = objectMapper.createObjectNode()
                .put("bookId", bookId)
                .put("memberId", memberId)
                .put("dueDate", LocalDate.now().plusDays(14).toString())
                .toString();

        return mockMvc.perform(post("/api/transactions/issue")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private static int status(MvcResult result) {
        return result.getResponse().getStatus();
    }

    private int availableCopies() {
        return bookRepository.findById(book.getId()).orElseThrow().getAvailableCopies();
    }

    /** Asserts a refusal left the shelf exactly as it was. */
    private void assertStockUntouched(String label) {
        assertThat(availableCopies()).as("%s must not move stock", label).isEqualTo(TOTAL_COPIES);
    }

    // ---------- lending works ----------

    @Test
    void staffIssueABookToAMemberOfTheirOwnLibrary() throws Exception {
        MvcResult result = issue(book.getId(), member.getId(), librarianToken);

        assertThat(status(result)).isEqualTo(201);
        assertThat(json(result).path("bookId").asLong()).isEqualTo(book.getId());
        assertThat(json(result).path("userId").asLong())
                .as("the loan belongs to the member")
                .isEqualTo(member.getId());
        assertThat(json(result).path("status").asText()).isEqualTo("ISSUED");
        assertThat(availableCopies()).as("one copy has left the shelf").isEqualTo(TOTAL_COPIES - 1);
    }

    @Test
    void theStaffMemberProcessingTheLoanIsNotTheBorrower() throws Exception {
        MvcResult result = issue(book.getId(), member.getId(), librarianToken);

        Transaction stored = transactionRepository.findById(json(result).path("id").asLong()).orElseThrow();

        assertThat(stored.getUser().getId()).isEqualTo(member.getId());
        assertThat(stored.getUser().getId())
                .as("the librarian handed the book over; they did not borrow it")
                .isNotEqualTo(librarian.getId());
        assertThat(stored.getBook().getId()).isEqualTo(book.getId());
        assertThat(stored.getLibrary().getId())
                .as("book, borrower and loan all belong to one library")
                .isEqualTo(libraryA.getId());
        assertThat(stored.getStatus()).isEqualTo(TransactionStatus.ISSUED);
    }

    @Test
    void bothAdminAndLibrarianMayLend() throws Exception {
        assertThat(status(issue(book.getId(), member.getId(), librarianToken))).isEqualTo(201);
        assertThat(status(issue(book.getId(), member.getId(), adminToken))).isEqualTo(201);

        assertThat(availableCopies()).isZero();
    }

    @Test
    void theLoanAppearsInTheMembersOwnHistory() throws Exception {
        long loanId = json(issue(book.getId(), member.getId(), librarianToken)).path("id").asLong();

        MvcResult history = mockMvc.perform(get("/api/transactions/user/{id}", member.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + librarianToken))
                .andReturn();

        assertThat(status(history)).isEqualTo(200);
        assertThat(json(history).path("content").path(0).path("id").asLong()).isEqualTo(loanId);
    }

    // ---------- who cannot be lent to ----------

    @Test
    void aMemberOfAnotherLibraryIsRefusedExactlyLikeAnIdThatDoesNotExist() throws Exception {
        MvcResult crossLibrary = issue(book.getId(), otherLibraryMember.getId(), librarianToken);
        MvcResult missing = issue(book.getId(), 999_999_999L, librarianToken);

        assertThat(status(crossLibrary)).isEqualTo(404);
        assertThat(json(crossLibrary).path("message").asText())
                .isEqualTo("User not found with id: " + otherLibraryMember.getId());
        assertThat(json(missing).path("message").asText().replaceAll("\\d+", "<id>"))
                .as("the two refusals differ only by the id echoed back")
                .isEqualTo(json(crossLibrary).path("message").asText().replaceAll("\\d+", "<id>"));
        assertStockUntouched("a cross-library borrower");
    }

    @Test
    void aStaffAccountCannotBeTheBorrower() throws Exception {
        for (User staff : new User[] {librarian, admin}) {
            MvcResult refused = issue(book.getId(), staff.getId(), librarianToken);

            assertThat(status(refused)).as("issuing to %s", staff.getUsername()).isEqualTo(400);
            assertThat(json(refused).path("message").asText())
                    .isEqualTo("Books can only be issued to an active member account.");
        }

        assertStockUntouched("a staff borrower");
    }

    @Test
    void aDisabledOrLockedMemberCannotBorrow() throws Exception {
        MvcResult disabled = issue(book.getId(), disabledMember.getId(), librarianToken);
        MvcResult locked = issue(book.getId(), lockedMember.getId(), librarianToken);

        assertThat(status(disabled)).isEqualTo(400);
        assertThat(status(locked)).isEqualTo(400);
        assertStockUntouched("an inactive member");
    }

    @Test
    void everyIneligibleBorrowerIsRefusedWithTheIdenticalMessage() throws Exception {
        // Staff, disabled and locked are three different reasons, and telling
        // them apart would make this endpoint a way to read an account's status.
        String staffAccount = json(issue(book.getId(), librarian.getId(), librarianToken)).path("message").asText();
        String disabled = json(issue(book.getId(), disabledMember.getId(), librarianToken)).path("message").asText();
        String locked = json(issue(book.getId(), lockedMember.getId(), librarianToken)).path("message").asText();

        assertThat(staffAccount).isEqualTo(disabled).isEqualTo(locked);
    }

    @Test
    void aRequestWithNoMemberIsRejectedBeforeAnythingHappens() throws Exception {
        String body = objectMapper.createObjectNode()
                .put("bookId", book.getId())
                .put("dueDate", LocalDate.now().plusDays(14).toString())
                .toString();

        MvcResult result = mockMvc.perform(post("/api/transactions/issue")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + librarianToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();

        assertThat(status(result)).isEqualTo(400);
        assertThat(json(result).path("message").asText()).isEqualTo("Member id is required");
        assertStockUntouched("a request naming no member");
    }

    // ---------- the existing rules still hold ----------

    @Test
    void aBookFromAnotherLibraryIsStillRefusedBeforeTheBorrowerMatters() throws Exception {
        Book theirBook = new Book();
        theirBook.setTitle("Step148 Other Library Book");
        theirBook.setAuthor("Author");
        theirBook.setIsbn("STEP148B-" + suffix);
        theirBook.setLibrary(otherLibraryMember.getLibrary());
        theirBook.setTotalCopies(1);
        theirBook.setAvailableCopies(1);
        theirBook = bookRepository.save(theirBook);

        MvcResult refused = issue(theirBook.getId(), member.getId(), librarianToken);

        assertThat(status(refused)).isEqualTo(404);
        assertThat(json(refused).path("message").asText())
                .isEqualTo("Book not found with id: " + theirBook.getId());
        assertThat(bookRepository.findById(theirBook.getId()).orElseThrow().getAvailableCopies())
                .as("the other library's stock is untouched")
                .isEqualTo(1);
    }

    @Test
    void aDueDateInThePastIsStillRefused() throws Exception {
        String body = objectMapper.createObjectNode()
                .put("bookId", book.getId())
                .put("memberId", member.getId())
                .put("dueDate", LocalDate.now().minusDays(1).toString())
                .toString();

        MvcResult result = mockMvc.perform(post("/api/transactions/issue")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + librarianToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();

        assertThat(status(result)).isEqualTo(400);
        assertStockUntouched("a due date in the past");
    }

    @Test
    void lendingTheLastCopyLeavesNoneAndTheNextRequestIsRefused() throws Exception {
        assertThat(status(issue(book.getId(), member.getId(), librarianToken))).isEqualTo(201);
        assertThat(status(issue(book.getId(), member.getId(), librarianToken))).isEqualTo(201);
        assertThat(availableCopies()).isZero();

        MvcResult refused = issue(book.getId(), member.getId(), librarianToken);

        assertThat(status(refused)).as("nothing left to lend").isEqualTo(409);
        assertThat(availableCopies()).isZero();
    }
}
