package com.library.lms.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

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
 * Proves overdue status and fines through the real API, on real rows.
 *
 * <p><b>Loans are written with their dates already set.</b> The API will only
 * issue a book due today or later, so an overdue loan cannot be made by issuing
 * one; the rows are saved directly, due some days ago, with the book's
 * available count lowered to match. Everything after that - reads, lists and
 * returns - goes through HTTP.</p>
 *
 * <p><b>The rate is 0.35</b>, pinned for this class so the expected amounts do
 * not depend on the environment, and chosen because {@code double} arithmetic
 * gets its multiples wrong. Amounts are compared exactly, from the JSON.</p>
 *
 * <p><b>Nothing is written by a read.</b> An overdue loan is looked at, listed
 * and looked at again, and the stored row - status, fine and version - is
 * unchanged. Only a return writes a fine, and it is then final.</p>
 *
 * <p><b>Isolation:</b> two libraries with overdue loans of their own; neither
 * can see, return or fine the other's. The throwaway schema the other
 * integration tests use, never the development database, with a fresh pair of
 * libraries for every test.</p>
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:mysql://localhost:3306/library_db_step129_it"
                + "?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true"
                + "&serverTimezone=UTC",
        "spring.jpa.hibernate.ddl-auto=update",
        "spring.jpa.open-in-view=false",
        "library.fines.daily-rate=0.35"
})
@AutoConfigureMockMvc
class OverdueFineIntegrationTest {

    /** Test-only credential, never a real one, and never reused outside this class. */
    private static final String TEST_PASSWORD = "step156-test-only-password";

    private static final int COPIES = 10;

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

    private record Tenant(Library library, User member, Long bookId, String librarianToken, String memberToken) {
    }

    private String suffix;
    private LocalDate today;
    private Tenant a;
    private Tenant b;

    // ---------- fixtures ----------

    @BeforeEach
    void createTwoLibraries() throws Exception {
        suffix = UUID.randomUUID().toString().substring(0, 8);
        today = LocalDate.now();

        a = tenant("A");
        b = tenant("B");
    }

    private Tenant tenant(String label) throws Exception {
        Library library = new Library();
        library.setName("Step156 Library " + label + " " + suffix);
        library = libraryRepository.save(library);

        User librarian = persistUser(library, Role.ROLE_LIBRARIAN, "librarian-" + label);
        User member = persistUser(library, Role.ROLE_MEMBER, "member-" + label);

        Book book = new Book();
        book.setTitle("Step156 Title " + label);
        book.setAuthor("Step156 Author");
        book.setIsbn("156-" + label + "-" + suffix);
        book.setTotalCopies(COPIES);
        book.setAvailableCopies(COPIES);
        book.setLibrary(library);
        book = bookRepository.save(book);

        return new Tenant(library, member, book.getId(), login(librarian), login(member));
    }

    private User persistUser(Library library, Role role, String label) {
        String username = "step156-" + label + "-" + suffix;

        User user = new User();
        user.setUsername(username);
        user.setEmail(username + "@example.invalid");
        user.setPassword(passwordEncoder.encode(TEST_PASSWORD));
        user.setRole(role);
        user.setLibrary(library);
        return userRepository.save(user);
    }

    /** An open loan due {@code dueInDays} from today - negative for the past - with its copy taken off the shelf. */
    private Transaction openLoan(Tenant tenant, int dueInDays, TransactionStatus stored) {
        Book book = bookRepository.findById(tenant.bookId()).orElseThrow();
        book.setAvailableCopies(book.getAvailableCopies() - 1);
        book = bookRepository.save(book);

        Transaction loan = new Transaction();
        loan.setBook(book);
        loan.setUser(tenant.member());
        loan.setLibrary(tenant.library());
        loan.setIssueDate(today.plusDays(Math.min(dueInDays, 0) - 14));
        loan.setDueDate(today.plusDays(dueInDays));
        loan.setStatus(stored);
        return transactionRepository.save(loan);
    }

    private Transaction openLoan(Tenant tenant, int dueInDays) {
        return openLoan(tenant, dueInDays, TransactionStatus.ISSUED);
    }

    /** A loan that has already come back, carrying whatever fine was stored for it. */
    private Transaction returnedLoan(Tenant tenant, int dueInDays, int returnedInDays, Double fine) {
        Transaction loan = new Transaction();
        loan.setBook(bookRepository.findById(tenant.bookId()).orElseThrow());
        loan.setUser(tenant.member());
        loan.setLibrary(tenant.library());
        loan.setIssueDate(today.plusDays(dueInDays - 14));
        loan.setDueDate(today.plusDays(dueInDays));
        loan.setReturnDate(today.plusDays(returnedInDays));
        loan.setFineAmount(fine);
        loan.setStatus(TransactionStatus.RETURNED);
        return transactionRepository.save(loan);
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

        assertThat(status(result)).as("login for %s", user.getUsername()).isEqualTo(200);
        return json(result).path("token").asText();
    }

    private MvcResult call(MockHttpServletRequestBuilder request, String token) throws Exception {
        return mockMvc.perform(request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)).andReturn();
    }

    private JsonNode loanAs(Transaction loan, String token) throws Exception {
        MvcResult result = call(get("/api/transactions/{id}", loan.getId()), token);
        assertThat(status(result)).as("GET loan %s", loan.getId()).isEqualTo(200);
        return json(result);
    }

    private MvcResult returnLoan(Transaction loan, String token) throws Exception {
        return call(post("/api/transactions/{id}/return", loan.getId()), token);
    }

    private JsonNode statusList(TransactionStatus status, Tenant tenant) throws Exception {
        MvcResult result = call(get("/api/transactions/status/{status}", status).param("size", "50"),
                tenant.librarianToken());
        assertThat(status(result)).as("list %s", status).isEqualTo(200);
        return json(result);
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private static int status(MvcResult result) {
        return result.getResponse().getStatus();
    }

    private static List<Long> ids(JsonNode page) {
        List<Long> ids = new ArrayList<>();
        page.path("content").forEach(item -> ids.add(item.path("id").asLong()));
        return ids;
    }

    /** The loan's status and fine, the fine compared exactly - or absent when {@code expectedFine} is null. */
    private static void assertStanding(JsonNode loan, TransactionStatus expectedStatus, String expectedFine) {
        assertThat(loan.path("status").asText())
                .as("status of loan %s", loan.path("id"))
                .isEqualTo(expectedStatus.name());

        JsonNode fine = loan.path("fineAmount");
        if (expectedFine == null) {
            assertThat(fine.isNull()).as("no fine on loan %s, but was %s", loan.path("id"), fine).isTrue();
        } else {
            assertThat(fine.isNumber()).as("fine on loan %s", loan.path("id")).isTrue();
            assertThat(fine.decimalValue())
                    .as("fine on loan %s, exactly - not a double approximation", loan.path("id"))
                    .isEqualByComparingTo(expectedFine);
        }
    }

    // ---------- overdue detection ----------

    @Test
    void anOpenLoanPastItsDueDateIsReportedOverdueWithItsFineSoFar() throws Exception {
        Transaction loan = openLoan(a, -3);

        for (String token : List.of(a.librarianToken(), a.memberToken())) {
            JsonNode body = loanAs(loan, token);

            assertStanding(body, TransactionStatus.OVERDUE, "1.05");
            assertThat(body.path("returnDate").isNull()).isTrue();
        }
    }

    @Test
    void aLoanBecomesOverdueTheDayAfterItsDueDate() throws Exception {
        assertStanding(loanAs(openLoan(a, 5), a.librarianToken()), TransactionStatus.ISSUED, null);
        assertStanding(loanAs(openLoan(a, 0), a.librarianToken()), TransactionStatus.ISSUED, null);
        assertStanding(loanAs(openLoan(a, -1), a.librarianToken()), TransactionStatus.OVERDUE, "0.35");
    }

    @Test
    void theIssuedAndOverdueListsDivideOpenLoansByDueDate() throws Exception {
        Transaction dueYesterday = openLoan(a, -1);
        Transaction dueTenDaysAgo = openLoan(a, -10);
        Transaction dueToday = openLoan(a, 0);
        Transaction dueNextWeek = openLoan(a, 7);
        Transaction returnedLate = returnedLoan(a, -20, -15, 1.75);

        JsonNode overdue = statusList(TransactionStatus.OVERDUE, a);
        assertThat(ids(overdue)).containsExactlyInAnyOrder(dueYesterday.getId(), dueTenDaysAgo.getId());
        assertThat(overdue.path("totalElements").asLong()).isEqualTo(2);
        for (JsonNode loan : overdue.path("content")) {
            String expected = loan.path("id").asLong() == dueYesterday.getId() ? "0.35" : "3.50";
            assertStanding(loan, TransactionStatus.OVERDUE, expected);
        }

        JsonNode issued = statusList(TransactionStatus.ISSUED, a);
        assertThat(ids(issued)).containsExactlyInAnyOrder(dueToday.getId(), dueNextWeek.getId());
        issued.path("content").forEach(loan -> assertStanding(loan, TransactionStatus.ISSUED, null));

        JsonNode returned = statusList(TransactionStatus.RETURNED, a);
        assertThat(ids(returned)).containsExactly(returnedLate.getId());
        assertStanding(returned.path("content").get(0), TransactionStatus.RETURNED, "1.75");

        MvcResult firstPage = call(get("/api/transactions/status/OVERDUE").param("size", "1"), a.librarianToken());
        assertThat(ids(json(firstPage))).hasSize(1);
        assertThat(json(firstPage).path("totalElements").asLong()).as("still paged").isEqualTo(2);
    }

    @Test
    void readingAnOverdueLoanNeverWritesToIt() throws Exception {
        Transaction loan = openLoan(a, -4);
        Long versionBefore = transactionRepository.findById(loan.getId()).orElseThrow().getVersion();

        for (int i = 0; i < 3; i++) {
            assertStanding(loanAs(loan, a.librarianToken()), TransactionStatus.OVERDUE, "1.40");
            statusList(TransactionStatus.OVERDUE, a);
        }

        Transaction stored = transactionRepository.findById(loan.getId()).orElseThrow();
        assertThat(stored.getStatus()).as("the stored state is untouched").isEqualTo(TransactionStatus.ISSUED);
        assertThat(stored.getFineAmount()).as("no fine is stored for an open loan").isNull();
        assertThat(stored.getVersion()).as("the row was never updated").isEqualTo(versionBefore);
    }

    // ---------- returns ----------

    @Test
    void returningAnOverdueLoanFixesItsFine() throws Exception {
        Transaction loan = openLoan(a, -4);
        int availableBefore = bookRepository.findById(a.bookId()).orElseThrow().getAvailableCopies();

        MvcResult returned = returnLoan(loan, a.librarianToken());

        assertThat(status(returned)).isEqualTo(200);
        assertStanding(json(returned), TransactionStatus.RETURNED, "1.40");
        assertThat(LocalDate.parse(json(returned).path("returnDate").asText())).isEqualTo(today);

        Transaction stored = transactionRepository.findById(loan.getId()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(TransactionStatus.RETURNED);
        assertThat(stored.getFineAmount()).isEqualTo(1.40);
        assertThat(bookRepository.findById(a.bookId()).orElseThrow().getAvailableCopies())
                .as("the copy is back on the shelf")
                .isEqualTo(availableBefore + 1);

        assertStanding(loanAs(loan, a.memberToken()), TransactionStatus.RETURNED, "1.40");

        MvcResult again = returnLoan(loan, a.librarianToken());
        assertThat(status(again)).as("a second return is refused").isEqualTo(409);
        assertThat(transactionRepository.findById(loan.getId()).orElseThrow().getFineAmount())
                .as("and the fine is not charged twice")
                .isEqualTo(1.40);
        assertThat(bookRepository.findById(a.bookId()).orElseThrow().getAvailableCopies())
                .isEqualTo(availableBefore + 1);
    }

    @Test
    void returningOnTimeOwesNothing() throws Exception {
        for (int dueInDays : new int[] {0, 3}) {
            Transaction loan = openLoan(a, dueInDays);

            MvcResult returned = returnLoan(loan, a.librarianToken());

            assertThat(status(returned)).isEqualTo(200);
            assertStanding(json(returned), TransactionStatus.RETURNED, "0.00");
            assertThat(transactionRepository.findById(loan.getId()).orElseThrow().getFineAmount())
                    .as("a fine worked out and found to be nothing, not one never assessed")
                    .isEqualTo(0.0);
        }
    }

    @Test
    void aReturnedLoansFineIsFinalHoweverLongAgoItWasDue() throws Exception {
        // Due thirty days ago and back two days later: 0.70 was owed. Reading
        // it today must not recount it to thirty days.
        Transaction returned = returnedLoan(a, -30, -28, 0.70);
        assertStanding(loanAs(returned, a.librarianToken()), TransactionStatus.RETURNED, "0.70");

        // Returned before fines were calculated: none was assessed, and none is invented now.
        Transaction beforeFines = returnedLoan(a, -60, -50, null);
        assertStanding(loanAs(beforeFines, a.librarianToken()), TransactionStatus.RETURNED, null);
    }

    @Test
    void aLoanStoredAsOverdueIsStillOpenAndCanBeReturned() throws Exception {
        Transaction loan = openLoan(a, -2, TransactionStatus.OVERDUE);

        assertStanding(loanAs(loan, a.librarianToken()), TransactionStatus.OVERDUE, "0.70");
        assertThat(ids(statusList(TransactionStatus.OVERDUE, a))).containsExactly(loan.getId());

        MvcResult returned = returnLoan(loan, a.librarianToken());
        assertThat(status(returned)).isEqualTo(200);
        assertStanding(json(returned), TransactionStatus.RETURNED, "0.70");
    }

    // ---------- isolation ----------

    @Test
    void overdueLoansAndFinesStayWithinTheirOwnLibrary() throws Exception {
        Transaction loanOfA = openLoan(a, -2);
        Transaction loanOfB = openLoan(b, -5);
        int bAvailable = bookRepository.findById(b.bookId()).orElseThrow().getAvailableCopies();

        assertThat(ids(statusList(TransactionStatus.OVERDUE, a))).containsExactly(loanOfA.getId());

        JsonNode bOverdue = statusList(TransactionStatus.OVERDUE, b);
        assertThat(ids(bOverdue)).containsExactly(loanOfB.getId());
        assertStanding(bOverdue.path("content").get(0), TransactionStatus.OVERDUE, "1.75");

        assertThat(status(call(get("/api/transactions/{id}", loanOfB.getId()), a.librarianToken())))
                .as("library A cannot read B's overdue loan")
                .isEqualTo(404);
        assertThat(status(returnLoan(loanOfB, a.librarianToken())))
                .as("nor return it, and so fix a fine on it")
                .isEqualTo(404);

        Transaction stored = transactionRepository.findById(loanOfB.getId()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(TransactionStatus.ISSUED);
        assertThat(stored.getReturnDate()).isNull();
        assertThat(stored.getFineAmount()).isNull();
        assertThat(bookRepository.findById(b.bookId()).orElseThrow().getAvailableCopies()).isEqualTo(bAvailable);
    }
}
