package com.library.lms.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.LocalDate;
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
import com.library.lms.entity.FinePaymentStatus;
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
 * Proves a fine's payment is tracked apart from its amount, recorded only by
 * staff of the loan's own library, and recorded once.
 *
 * <p><b>No gateway, so no member.</b> A payment here is a member of staff's
 * record that money was received. A member cannot make that record for their
 * own fine - it would clear the fine without paying it - and neither can an
 * anonymous caller.</p>
 *
 * <p><b>Nothing changes on refusal.</b> Every refused payment - a second one, a
 * book still out, nothing owed, another library's loan, a caller who is not
 * staff - is followed by reading the stored row back: still unpaid, or still
 * paid at the same moment by the same person, with the same version.</p>
 *
 * <p>Loans that need a past due date are written directly, as in
 * {@code OverdueFineIntegrationTest}; returns and payments go through HTTP. The
 * rate is 0.35 so that amounts are exact.</p>
 *
 * <p><b>Isolation:</b> two libraries, fresh for every test, in the throwaway
 * schema the other integration tests use - never the development database.</p>
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
class FinePaymentIntegrationTest {

    /** Test-only credential, never a real one, and never reused outside this class. */
    private static final String TEST_PASSWORD = "step157-test-only-password";

    private static final int COPIES = 10;

    private static final String ALREADY_PAID = "This fine has already been paid.";

    private static final String NOTHING_OWED = "There is no fine to pay on this loan.";

    private static final String STILL_OUT = "A fine can be paid only after the book has been returned.";

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

    private record Tenant(Library library, User admin, User librarian, User member, Long bookId,
                          String adminToken, String librarianToken, String memberToken) {
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
        library.setName("Step157 Library " + label + " " + suffix);
        library = libraryRepository.save(library);

        User admin = persistUser(library, Role.ROLE_ADMIN, "admin-" + label);
        User librarian = persistUser(library, Role.ROLE_LIBRARIAN, "librarian-" + label);
        User member = persistUser(library, Role.ROLE_MEMBER, "member-" + label);

        Book book = new Book();
        book.setTitle("Step157 Title " + label);
        book.setAuthor("Step157 Author");
        book.setIsbn("157-" + label + "-" + suffix);
        book.setTotalCopies(COPIES);
        book.setAvailableCopies(COPIES);
        book.setLibrary(library);
        book = bookRepository.save(book);

        return new Tenant(library, admin, librarian, member, book.getId(),
                login(admin), login(librarian), login(member));
    }

    private User persistUser(Library library, Role role, String label) {
        String username = "step157-" + label + "-" + suffix;

        User user = new User();
        user.setUsername(username);
        user.setEmail(username + "@example.invalid");
        user.setPassword(passwordEncoder.encode(TEST_PASSWORD));
        user.setRole(role);
        user.setLibrary(library);
        return userRepository.save(user);
    }

    /** An open loan due {@code dueInDays} from today - negative for the past - with its copy off the shelf. */
    private Transaction openLoan(Tenant tenant, int dueInDays) {
        Book book = bookRepository.findById(tenant.bookId()).orElseThrow();
        book.setAvailableCopies(book.getAvailableCopies() - 1);
        book = bookRepository.save(book);

        Transaction loan = new Transaction();
        loan.setBook(book);
        loan.setUser(tenant.member());
        loan.setLibrary(tenant.library());
        loan.setIssueDate(today.plusDays(Math.min(dueInDays, 0) - 14));
        loan.setDueDate(today.plusDays(dueInDays));
        loan.setStatus(TransactionStatus.ISSUED);
        return transactionRepository.save(loan);
    }

    /** A loan returned {@code daysLate} days after its due date, through the API. */
    private Transaction returnedLate(Tenant tenant, int daysLate) throws Exception {
        Transaction loan = openLoan(tenant, -daysLate);
        assertThat(status(returnLoan(loan, tenant.librarianToken()))).as("return").isEqualTo(200);
        return loan;
    }

    /** A loan returned before payment was tracked: a fine, but no payment state. */
    private Transaction returnedBeforeTracking(Tenant tenant, Double fine) {
        Transaction loan = new Transaction();
        loan.setBook(bookRepository.findById(tenant.bookId()).orElseThrow());
        loan.setUser(tenant.member());
        loan.setLibrary(tenant.library());
        loan.setIssueDate(today.minusDays(30));
        loan.setDueDate(today.minusDays(16));
        loan.setReturnDate(today.minusDays(14));
        loan.setFineAmount(fine);
        loan.setStatus(TransactionStatus.RETURNED);
        return transactionRepository.save(loan);
    }

    private Transaction stored(Transaction loan) {
        return transactionRepository.findById(loan.getId()).orElseThrow();
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

    private MvcResult returnLoan(Transaction loan, String token) throws Exception {
        return call(post("/api/transactions/{id}/return", loan.getId()), token);
    }

    private MvcResult pay(Transaction loan, String token) throws Exception {
        return call(post("/api/transactions/{id}/fine-payment", loan.getId()), token);
    }

    private JsonNode loanAs(Transaction loan, String token) throws Exception {
        MvcResult result = call(get("/api/transactions/{id}", loan.getId()), token);
        assertThat(status(result)).as("GET loan %s", loan.getId()).isEqualTo(200);
        return json(result);
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private static int status(MvcResult result) {
        return result.getResponse().getStatus();
    }

    private void assertError(MvcResult result, int expectedStatus, String expectedMessage) throws Exception {
        assertThat(status(result)).isEqualTo(expectedStatus);
        assertThat(json(result).path("status").asInt()).isEqualTo(expectedStatus);
        assertThat(json(result).path("message").asText()).isEqualTo(expectedMessage);
    }

    /** The loan's payment state as the API reports it: a payment time exactly when PAID. */
    private static void assertPayment(JsonNode loan, FinePaymentStatus expected) {
        JsonNode state = loan.path("finePaymentStatus");

        if (expected == null) {
            assertThat(state.isNull()).as("no payment state on loan %s, but was %s", loan.path("id"), state).isTrue();
        } else {
            assertThat(state.asText()).as("payment state of loan %s", loan.path("id")).isEqualTo(expected.name());
        }

        assertThat(loan.path("finePaidAt").isNull())
                .as("a payment time on loan %s exactly when it is paid", loan.path("id"))
                .isEqualTo(expected != FinePaymentStatus.PAID);
    }

    private static void assertFine(JsonNode loan, String expected) {
        assertThat(loan.path("fineAmount").decimalValue()).isEqualByComparingTo(expected);
    }

    // ---------- unpaid ----------

    @Test
    void aFineOwedStartsUnpaid() throws Exception {
        Transaction loan = openLoan(a, -4);

        JsonNode stillOut = loanAs(loan, a.memberToken());
        assertFine(stillOut, "1.40");
        assertPayment(stillOut, FinePaymentStatus.UNPAID);

        MvcResult returned = returnLoan(loan, a.librarianToken());
        assertThat(status(returned)).isEqualTo(200);
        assertThat(json(returned).path("status").asText()).isEqualTo("RETURNED");
        assertFine(json(returned), "1.40");
        assertPayment(json(returned), FinePaymentStatus.UNPAID);

        Transaction row = stored(loan);
        assertThat(row.getFinePaymentStatus()).isEqualTo(FinePaymentStatus.UNPAID);
        assertThat(row.getFinePaidAt()).isNull();
        assertThat(row.getFinePaymentRecordedBy()).isNull();
    }

    @Test
    void aFineOfNothingNeedsNoPayment() throws Exception {
        assertPayment(loanAs(openLoan(a, 3), a.memberToken()), null);

        Transaction onTime = openLoan(a, 0);
        MvcResult returned = returnLoan(onTime, a.librarianToken());
        assertFine(json(returned), "0.00");
        assertPayment(json(returned), FinePaymentStatus.NOT_REQUIRED);

        assertError(pay(onTime, a.librarianToken()), 409, NOTHING_OWED);

        Transaction row = stored(onTime);
        assertThat(row.getFinePaymentStatus()).isEqualTo(FinePaymentStatus.NOT_REQUIRED);
        assertThat(row.getFinePaidAt()).isNull();
    }

    // ---------- payment ----------

    @Test
    void staffRecordAPaymentWithoutChangingTheFine() throws Exception {
        Transaction loan = returnedLate(a, 4);
        LocalDate returnDate = stored(loan).getReturnDate();

        MvcResult paid = pay(loan, a.librarianToken());

        assertThat(status(paid)).isEqualTo(200);
        assertThat(json(paid).path("status").asText()).isEqualTo("RETURNED");
        assertFine(json(paid), "1.40");
        assertPayment(json(paid), FinePaymentStatus.PAID);

        Transaction row = stored(loan);
        assertThat(row.getFinePaymentStatus()).isEqualTo(FinePaymentStatus.PAID);
        assertThat(row.getFinePaidAt()).isNotNull();
        assertThat(row.getFinePaymentRecordedBy().getId()).as("who recorded it").isEqualTo(a.librarian().getId());
        assertThat(row.getFineAmount()).as("the amount owed is untouched").isEqualTo(1.40);
        assertThat(row.getStatus()).isEqualTo(TransactionStatus.RETURNED);
        assertThat(row.getReturnDate()).isEqualTo(returnDate);

        assertPayment(loanAs(loan, a.memberToken()), FinePaymentStatus.PAID);

        Transaction another = returnedLate(a, 2);
        assertThat(status(pay(another, a.adminToken()))).as("an administrator may record one too").isEqualTo(200);
        assertThat(stored(another).getFinePaymentRecordedBy().getId()).isEqualTo(a.admin().getId());
    }

    // ---------- duplicate payment ----------

    @Test
    void aFineCannotBePaidTwice() throws Exception {
        Transaction loan = returnedLate(a, 4);
        assertThat(status(pay(loan, a.librarianToken()))).isEqualTo(200);
        Transaction first = stored(loan);

        for (String token : List.of(a.librarianToken(), a.adminToken())) {
            assertError(pay(loan, token), 409, ALREADY_PAID);
        }

        Transaction after = stored(loan);
        assertThat(after.getFinePaymentStatus()).isEqualTo(FinePaymentStatus.PAID);
        assertThat(after.getFinePaidAt()).as("the first payment's time is kept").isEqualTo(first.getFinePaidAt());
        assertThat(after.getFinePaymentRecordedBy().getId())
                .as("and so is who recorded it")
                .isEqualTo(a.librarian().getId());
        assertThat(after.getVersion()).as("the row was not written again").isEqualTo(first.getVersion());
    }

    @Test
    void aFineCannotBePaidWhileTheBookIsStillOut() throws Exception {
        Transaction loan = openLoan(a, -6);

        assertError(pay(loan, a.librarianToken()), 409, STILL_OUT);

        Transaction row = stored(loan);
        assertThat(row.getStatus()).isEqualTo(TransactionStatus.ISSUED);
        assertThat(row.getFinePaymentStatus()).isNull();
        assertThat(row.getFinePaidAt()).isNull();
        assertPayment(loanAs(loan, a.librarianToken()), FinePaymentStatus.UNPAID);
    }

    @Test
    void aFineFromBeforePaymentTrackingIsJudgedByItsAmount() throws Exception {
        Transaction owed = returnedBeforeTracking(a, 0.70);
        assertPayment(loanAs(owed, a.librarianToken()), FinePaymentStatus.UNPAID);
        assertThat(status(pay(owed, a.librarianToken()))).isEqualTo(200);
        assertThat(stored(owed).getFinePaymentStatus()).isEqualTo(FinePaymentStatus.PAID);

        Transaction zero = returnedBeforeTracking(a, 0.0);
        assertPayment(loanAs(zero, a.librarianToken()), FinePaymentStatus.NOT_REQUIRED);
        assertError(pay(zero, a.librarianToken()), 409, NOTHING_OWED);

        Transaction neverAssessed = returnedBeforeTracking(a, null);
        assertPayment(loanAs(neverAssessed, a.librarianToken()), null);
        assertError(pay(neverAssessed, a.librarianToken()), 409, NOTHING_OWED);
        assertThat(stored(neverAssessed).getFinePaymentStatus()).isNull();
    }

    // ---------- who may record it ----------

    @Test
    void onlyStaffMayRecordAPayment() throws Exception {
        Transaction loan = returnedLate(a, 3);

        assertError(pay(loan, a.memberToken()), 403, "Access denied.");
        assertThat(status(call(get("/api/transactions/{id}/fine-payment", loan.getId()), a.memberToken())))
                .as("every verb on the path is staff only")
                .isEqualTo(403);

        MvcResult anonymous = mockMvc.perform(post("/api/transactions/{id}/fine-payment", loan.getId())).andReturn();
        assertThat(status(anonymous)).isEqualTo(401);

        Transaction row = stored(loan);
        assertThat(row.getFinePaymentStatus()).isEqualTo(FinePaymentStatus.UNPAID);
        assertThat(row.getFinePaidAt()).isNull();
        assertThat(row.getFinePaymentRecordedBy()).isNull();
    }

    // ---------- tenant isolation ----------

    @Test
    void aFineCannotBePaidInAnotherLibrary() throws Exception {
        Transaction loanOfB = returnedLate(b, 5);
        Long versionBefore = stored(loanOfB).getVersion();

        for (String token : List.of(a.librarianToken(), a.adminToken())) {
            assertThat(status(pay(loanOfB, token)))
                    .as("library A's staff are told the loan does not exist")
                    .isEqualTo(404);
        }

        Transaction untouched = stored(loanOfB);
        assertThat(untouched.getFinePaymentStatus()).isEqualTo(FinePaymentStatus.UNPAID);
        assertThat(untouched.getFinePaidAt()).isNull();
        assertThat(untouched.getFinePaymentRecordedBy()).isNull();
        assertThat(untouched.getVersion()).isEqualTo(versionBefore);

        MvcResult byOwnStaff = pay(loanOfB, b.librarianToken());
        assertThat(status(byOwnStaff)).as("its own library's staff may").isEqualTo(200);
        assertFine(json(byOwnStaff), "1.75");
        assertPayment(json(byOwnStaff), FinePaymentStatus.PAID);
        assertThat(stored(loanOfB).getFinePaymentRecordedBy().getId()).isEqualTo(b.librarian().getId());
    }
}
