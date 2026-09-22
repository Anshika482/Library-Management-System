package com.library.lms.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.library.lms.entity.AuditAction;
import com.library.lms.entity.AuditEvent;
import com.library.lms.entity.AuditOutcome;
import com.library.lms.entity.AuditTargetType;
import com.library.lms.entity.Book;
import com.library.lms.entity.FinePaymentStatus;
import com.library.lms.entity.Library;
import com.library.lms.entity.Payment;
import com.library.lms.entity.PaymentStatus;
import com.library.lms.entity.Role;
import com.library.lms.entity.Transaction;
import com.library.lms.entity.TransactionStatus;
import com.library.lms.entity.User;
import com.library.lms.repository.AuditEventRepository;
import com.library.lms.repository.BookRepository;
import com.library.lms.repository.LibraryRepository;
import com.library.lms.repository.PaymentRepository;
import com.library.lms.repository.TransactionRepository;
import com.library.lms.repository.UserRepository;

/**
 * Paying a fine by card, over HTTP, end to end.
 *
 * <p><b>The provider is played by this test</b>, which is the whole point: it
 * signs what a real provider would sign, with the same secret the application
 * is configured with, and the application decides for itself whether to believe
 * it. A payment the test signs with the wrong secret, or alters after signing,
 * must not move the fine.</p>
 *
 * <p><b>The desk flow is checked here too</b>, because the two must not
 * interfere: a fine settled at the desk cannot then be paid by card, and a fine
 * paid by card is refused at the desk afterwards.</p>
 *
 * <p><b>A small connection pool, closed when this class is done</b>, for the
 * reason the mail tests give: a cached context holds its pool open for the rest
 * of the suite, and the server has a limit the suite is already close to.</p>
 *
 * <p><b>Isolation:</b> the throwaway schema the other integration tests use,
 * never the development database.</p>
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:mysql://localhost:3306/library_db_step133_it"
                + "?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true"
                + "&serverTimezone=UTC",
        "spring.datasource.hikari.maximum-pool-size=2",
        "spring.jpa.hibernate.ddl-auto=update",
        "spring.jpa.open-in-view=false",
        "library.fines.daily-rate=1.00",
        // Test-only merchant credentials, never a real provider's.
        "payment.gateway.provider=hmac-sandbox",
        "payment.gateway.key-id=test_key_id",
        "payment.gateway.key-secret=test-only-gateway-secret-for-integration",
        "payment.currency=INR"
})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class FinePaymentGatewayIntegrationTest {

    /** Test-only credentials, never real ones. */
    private static final String PASSWORD = "gateway-test-only-password";

    private static final String KEY_SECRET = "test-only-gateway-secret-for-integration";

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
    private TransactionRepository transactionRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private AuditEventRepository auditEventRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private record Tenant(Library library, User librarian, User member, User otherMember, Long bookId,
                          String librarianToken, String memberToken, String otherMemberToken) {
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
        library.setName("Gateway Library " + label + " " + suffix);
        library = libraryRepository.save(library);

        User librarian = persistUser(library, "gw-" + suffix + "-librarian", Role.ROLE_LIBRARIAN);
        User member = persistUser(library, "gw-" + suffix + "-member", Role.ROLE_MEMBER);
        User otherMember = persistUser(library, "gw-" + suffix + "-other", Role.ROLE_MEMBER);

        Book book = new Book();
        book.setTitle("Gateway Book " + suffix);
        book.setAuthor("An Author");
        book.setIsbn("gw-" + label + "-" + suffix);
        book.setTotalCopies(5);
        book.setAvailableCopies(5);
        book.setLibrary(library);
        book = bookRepository.save(book);

        return new Tenant(library, librarian, member, otherMember, book.getId(),
                login(librarian), login(member), login(otherMember));
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

    /** A returned loan with a fine of five days, owed and unpaid. */
    private Transaction finedLoan(Tenant tenant) {
        Transaction loan = new Transaction();
        loan.setBook(bookRepository.findById(tenant.bookId()).orElseThrow());
        loan.setUser(tenant.member());
        loan.setLibrary(tenant.library());
        loan.setIssueDate(LocalDate.now().minusDays(20));
        loan.setDueDate(LocalDate.now().minusDays(5));
        loan.setReturnDate(LocalDate.now());
        loan.setFineAmount(5.0);
        loan.setFinePaymentStatus(FinePaymentStatus.UNPAID);
        loan.setStatus(TransactionStatus.RETURNED);
        return transactionRepository.save(loan);
    }

    private String login(User user) throws Exception {
        String body = objectMapper.createObjectNode()
                .put("username", user.getUsername())
                .put("password", PASSWORD)
                .toString();
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();

        assertThat(status(result)).as("login for %s", user.getUsername()).isEqualTo(200);
        return json(result).path("token").asText();
    }

    private MvcResult call(MockHttpServletRequestBuilder request, String token) throws Exception {
        return mockMvc.perform(request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)).andReturn();
    }

    private MvcResult order(Transaction loan, String token) throws Exception {
        return call(post("/api/transactions/{id}/payment-order", loan.getId()), token);
    }

    private MvcResult verify(Transaction loan, String orderId, String paymentId, String signature, String token)
            throws Exception {
        String body = objectMapper.createObjectNode()
                .put("providerOrderId", orderId)
                .put("providerPaymentId", paymentId)
                .put("signature", signature)
                .toString();

        return call(post("/api/transactions/{id}/payment-verification", loan.getId())
                .contentType(MediaType.APPLICATION_JSON).content(body), token);
    }

    /** What the provider would return: HMAC-SHA256 over "order|payment" under the configured secret. */
    private static String sign(String secret, String orderId, String paymentId) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));

        return HexFormat.of().formatHex(mac.doFinal((orderId + "|" + paymentId).getBytes(StandardCharsets.UTF_8)));
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private static int status(MvcResult result) {
        return result.getResponse().getStatus();
    }

    private Transaction stored(Transaction loan) {
        return transactionRepository.findById(loan.getId()).orElseThrow();
    }

    private List<AuditEvent> loanEvents(Tenant tenant, Long loanId) {
        return auditEventRepository.findByLibraryIdAndTargetTypeAndTargetIdOrderByIdAsc(
                tenant.library().getId(), AuditTargetType.LOAN, loanId);
    }

    /** Pays a loan's fine the whole way round, and returns the verification response. */
    private MvcResult payInFull(Transaction loan, String token) throws Exception {
        MvcResult opened = order(loan, token);
        assertThat(status(opened)).as("order").isEqualTo(200);

        String orderId = json(opened).path("providerOrderId").asText();
        String paymentId = "pay_" + UUID.randomUUID().toString().replace("-", "");

        return verify(loan, orderId, paymentId, sign(KEY_SECRET, orderId, paymentId), token);
    }

    // ---------- opening an order ----------

    @Test
    void anOrderCarriesTheAmountTheCurrencyAndThePublicKeyOnly() throws Exception {
        Transaction loan = finedLoan(a);

        JsonNode body = json(order(loan, a.memberToken()));

        assertThat(body.path("amount").asDouble()).isEqualTo(5.00);
        assertThat(body.path("currency").asText()).isEqualTo("INR");
        assertThat(body.path("keyId").asText()).isEqualTo("test_key_id");
        assertThat(body.path("providerOrderId").asText()).startsWith("order_");
        assertThat(body.path("loanId").asLong()).isEqualTo(loan.getId());
        assertThat(body.toString())
                .as("the secret has no route into any response")
                .doesNotContain(KEY_SECRET);
    }

    @Test
    void askingTwiceOpensOneOrder() throws Exception {
        Transaction loan = finedLoan(a);

        String first = json(order(loan, a.memberToken())).path("providerOrderId").asText();
        String second = json(order(loan, a.memberToken())).path("providerOrderId").asText();

        assertThat(second).isEqualTo(first);
        assertThat(paymentRepository.findAll().stream()
                .filter(payment -> payment.getTransaction().getId().equals(loan.getId()))
                .toList())
                .hasSize(1);
    }

    @Test
    void staffMayOpenAnOrderAndAnotherMemberMayNot() throws Exception {
        Transaction loan = finedLoan(a);

        assertThat(status(order(loan, a.librarianToken()))).isEqualTo(200);
        assertThat(status(order(loan, a.otherMemberToken())))
                .as("a member may pay their own fine and nobody else's")
                .isEqualTo(403);
    }

    // ---------- the happy path ----------

    @Test
    void averifiedPaymentMarksTheFinePaidAndIsAudited() throws Exception {
        Transaction loan = finedLoan(a);

        MvcResult settled = payInFull(loan, a.memberToken());

        assertThat(status(settled)).isEqualTo(200);
        assertThat(json(settled).path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(json(settled).path("finePaymentStatus").asText()).isEqualTo("PAID");

        Transaction after = stored(loan);
        assertThat(after.getFinePaymentStatus()).isEqualTo(FinePaymentStatus.PAID);
        assertThat(after.getFinePaidAt()).isNotNull();
        assertThat(after.getFinePaymentRecordedBy().getId()).isEqualTo(a.member().getId());

        assertThat(loanEvents(a, loan.getId()))
                .extracting(AuditEvent::getAction, AuditEvent::getOutcome, AuditEvent::getActorUserId)
                .containsExactly(org.assertj.core.api.Assertions.tuple(
                        AuditAction.FINE_PAID, AuditOutcome.SUCCESS, a.member().getId()));
    }

    // ---------- what must not settle a fine ----------

    @Test
    void aSignatureFromTheWrongSecretIsRefusedAndTheFineStands() throws Exception {
        Transaction loan = finedLoan(a);
        String orderId = json(order(loan, a.memberToken())).path("providerOrderId").asText();
        String paymentId = "pay_forged";

        MvcResult refused = verify(loan, orderId, paymentId,
                sign("a-secret-the-attacker-guessed", orderId, paymentId), a.memberToken());

        assertThat(status(refused)).isEqualTo(400);
        assertThat(json(refused).path("message").asText()).isEqualTo("The payment could not be verified.");
        assertThat(stored(loan).getFinePaymentStatus()).isEqualTo(FinePaymentStatus.UNPAID);
        assertThat(loanEvents(a, loan.getId()))
                .extracting(AuditEvent::getOutcome)
                .containsExactly(AuditOutcome.FAILURE);
    }

    @Test
    void aTamperedPaymentReferenceIsRefused() throws Exception {
        Transaction loan = finedLoan(a);
        String orderId = json(order(loan, a.memberToken())).path("providerOrderId").asText();
        String signature = sign(KEY_SECRET, orderId, "pay_real");

        MvcResult refused = verify(loan, orderId, "pay_altered", signature, a.memberToken());

        assertThat(status(refused)).isEqualTo(400);
        assertThat(stored(loan).getFinePaymentStatus()).isEqualTo(FinePaymentStatus.UNPAID);
    }

    @Test
    void aFailedAttemptIsRecordedAsAFailedPayment() throws Exception {
        Transaction loan = finedLoan(a);
        String orderId = json(order(loan, a.memberToken())).path("providerOrderId").asText();

        assertThat(status(verify(loan, orderId, "pay_x", "00ff", a.memberToken()))).isEqualTo(400);

        Payment payment = paymentRepository.findByLibraryIdAndProviderOrderId(a.library().getId(), orderId)
                .orElseThrow();
        assertThat(payment.getStatus())
                .as("the attempt is kept, which is the point of tracking status")
                .isEqualTo(PaymentStatus.FAILED);
        assertThat(payment.getProviderPaymentId()).as("nothing was paid, so nothing is referenced").isNull();
    }

    // ---------- twice ----------

    @Test
    void theSameVerifiedPaymentSentAgainSettlesNothingTwice() throws Exception {
        Transaction loan = finedLoan(a);
        MvcResult opened = order(loan, a.memberToken());
        String orderId = json(opened).path("providerOrderId").asText();
        String paymentId = "pay_" + UUID.randomUUID().toString().replace("-", "");
        String signature = sign(KEY_SECRET, orderId, paymentId);

        assertThat(status(verify(loan, orderId, paymentId, signature, a.memberToken()))).isEqualTo(200);
        java.time.LocalDateTime firstPaidAt = stored(loan).getFinePaidAt();

        MvcResult again = verify(loan, orderId, paymentId, signature, a.memberToken());

        assertThat(status(again)).as("a replay is answered, not refused").isEqualTo(200);
        assertThat(json(again).path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(stored(loan).getFinePaidAt()).isEqualTo(firstPaidAt);
        assertThat(loanEvents(a, loan.getId())).as("one payment, one event").hasSize(1);
    }

    @Test
    void aFinePaidByCardCannotThenBePaidAtTheDesk() throws Exception {
        Transaction loan = finedLoan(a);
        assertThat(status(payInFull(loan, a.memberToken()))).isEqualTo(200);

        MvcResult atTheDesk = call(post("/api/transactions/{id}/fine-payment", loan.getId()), a.librarianToken());

        assertThat(status(atTheDesk)).as("the desk flow refuses it exactly as it always did").isEqualTo(409);
    }

    @Test
    void aFinePaidAtTheDeskCannotThenBePaidByCard() throws Exception {
        Transaction loan = finedLoan(a);
        String orderId = json(order(loan, a.memberToken())).path("providerOrderId").asText();

        assertThat(status(call(post("/api/transactions/{id}/fine-payment", loan.getId()), a.librarianToken())))
                .isEqualTo(200);

        String paymentId = "pay_late";
        MvcResult tooLate = verify(loan, orderId, paymentId, sign(KEY_SECRET, orderId, paymentId), a.memberToken());

        assertThat(status(tooLate)).as("verified, but there is nothing left to pay").isEqualTo(409);
        assertThat(stored(loan).getFinePaymentRecordedBy().getId())
                .as("and the desk payment stands as it was recorded")
                .isEqualTo(a.librarian().getId());
    }

    // ---------- one library only ----------

    @Test
    void aLoanOfAnotherLibraryIsNotPayable() throws Exception {
        Transaction loanOfB = finedLoan(b);

        assertThat(status(order(loanOfB, a.memberToken()))).as("not found, not forbidden").isEqualTo(404);
        assertThat(status(order(loanOfB, a.librarianToken()))).isEqualTo(404);
    }

    @Test
    void anOrderCannotBeVerifiedFromAnotherLibrary() throws Exception {
        Transaction loanOfB = finedLoan(b);
        String orderId = json(order(loanOfB, b.memberToken())).path("providerOrderId").asText();
        String paymentId = "pay_cross";
        String signature = sign(KEY_SECRET, orderId, paymentId);

        MvcResult refused = verify(loanOfB, orderId, paymentId, signature, a.memberToken());

        assertThat(status(refused))
                .as("B's order is invisible to A, signature or no signature")
                .isEqualTo(400);
        assertThat(stored(loanOfB).getFinePaymentStatus()).isEqualTo(FinePaymentStatus.UNPAID);
    }

    @Test
    void anonymousCallersReachNeitherEndpoint() throws Exception {
        Transaction loan = finedLoan(a);

        assertThat(status(mockMvc.perform(post("/api/transactions/{id}/payment-order", loan.getId())).andReturn()))
                .isEqualTo(401);
        assertThat(status(mockMvc.perform(post("/api/transactions/{id}/payment-verification", loan.getId())
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andReturn()))
                .isEqualTo(401);
    }

    // ---------- hygiene ----------

    @Test
    void noPaymentRowCanHoldCardData() {
        List<String> columns = java.util.Arrays.stream(Payment.class.getDeclaredFields())
                .filter(field -> !java.lang.reflect.Modifier.isStatic(field.getModifiers()))
                .map(java.lang.reflect.Field::getName)
                .toList();

        assertThat(columns)
                .as("references, an amount, a status and who started it - nothing else")
                .containsExactlyInAnyOrder("id", "version", "library", "transaction", "initiatedBy", "provider",
                        "providerOrderId", "providerPaymentId", "amount", "currency", "status", "createdAt",
                        "completedAt");
        assertThat(String.join(" ", columns).toLowerCase(java.util.Locale.ROOT))
                .doesNotContain("card")
                .doesNotContain("cvv")
                .doesNotContain("pan")
                .doesNotContain("secret")
                .doesNotContain("expiry");
    }

    @Test
    void theGatewaySecretIsInNoPaymentRowAndNoAuditEvent() throws Exception {
        Transaction loan = finedLoan(a);
        assertThat(status(payInFull(loan, a.memberToken()))).isEqualTo(200);

        assertThat(paymentRepository.findAll().toString()).doesNotContain(KEY_SECRET);
        assertThat(auditEventRepository.findByLibraryId(a.library().getId(), PageRequest.of(0, 50))
                .getContent().toString()).doesNotContain(KEY_SECRET);
    }
}
