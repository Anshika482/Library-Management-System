package com.library.lms.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.library.lms.dto.PaymentOrderResponse;
import com.library.lms.dto.PaymentResponse;
import com.library.lms.dto.PaymentVerificationRequest;
import com.library.lms.entity.AuditAction;
import com.library.lms.entity.FinePaymentStatus;
import com.library.lms.entity.Library;
import com.library.lms.entity.Payment;
import com.library.lms.entity.PaymentStatus;
import com.library.lms.entity.Role;
import com.library.lms.entity.Transaction;
import com.library.lms.entity.TransactionStatus;
import com.library.lms.entity.User;
import com.library.lms.exception.FinePaymentNotAllowedException;
import com.library.lms.exception.PaymentGatewayNotConfiguredException;
import com.library.lms.exception.PaymentVerificationFailedException;
import com.library.lms.exception.TransactionAccessDeniedException;
import com.library.lms.exception.TransactionNotFoundException;
import com.library.lms.repository.PaymentRepository;
import com.library.lms.repository.TransactionRepository;
import com.library.lms.repository.UserRepository;

/**
 * What decides whether a fine is marked paid, with no database and no provider.
 *
 * <p>The gateway is a mock, so "the signature verified" and "it did not" are
 * both exact; what matters here is what the service does with each answer, who
 * it lets pay, and what it refuses to do twice.</p>
 */
class PaymentServiceTest {

    private static final long LIBRARY_ID = 7L;

    private static final long LOAN_ID = 55L;

    private static final String ORDER = "order_abc";

    private static final String PAYMENT_REF = "pay_xyz";

    private static final String SIGNATURE = "0a1b";

    private final PaymentRepository paymentRepository = mock(PaymentRepository.class);

    private final TransactionRepository transactionRepository = mock(TransactionRepository.class);

    private final UserRepository userRepository = mock(UserRepository.class);

    private final PaymentGateway gateway = mock(PaymentGateway.class);

    private final AuditService auditService = mock(AuditService.class);

    private final PaymentService service = new PaymentService(paymentRepository, transactionRepository,
            userRepository, gateway, auditService, "INR");

    private Library library;
    private User member;
    private User otherMember;
    private User librarian;
    private Transaction loan;

    @BeforeEach
    void fixtures() {
        library = new Library();
        library.setId(LIBRARY_ID);

        member = account(21L, "member", Role.ROLE_MEMBER);
        otherMember = account(22L, "other", Role.ROLE_MEMBER);
        librarian = account(11L, "librarian", Role.ROLE_LIBRARIAN);

        loan = new Transaction();
        loan.setId(LOAN_ID);
        loan.setLibrary(library);
        loan.setUser(member);
        loan.setIssueDate(LocalDate.now().minusDays(20));
        loan.setDueDate(LocalDate.now().minusDays(5));
        loan.setReturnDate(LocalDate.now());
        loan.setStatus(TransactionStatus.RETURNED);
        loan.setFineAmount(5.0);
        loan.setFinePaymentStatus(FinePaymentStatus.UNPAID);

        when(transactionRepository.findByIdAndLibraryId(LOAN_ID, LIBRARY_ID)).thenReturn(Optional.of(loan));
        when(gateway.configured()).thenReturn(true);
        when(gateway.name()).thenReturn("hmac-sandbox");
        when(gateway.createOrder(anyString(), any(), anyString())).thenReturn(ORDER);
        when(paymentRepository.save(any(Payment.class))).thenAnswer(call -> {
            Payment saved = call.getArgument(0);
            if (saved.getId() == null) {
                saved.setId(900L);
            }
            return saved;
        });
    }

    private User account(long id, String username, Role role) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        user.setRole(role);
        user.setLibrary(library);
        when(userRepository.findByUsername(username)).thenReturn(Optional.of(user));
        return user;
    }

    /** An order already open for the loan. */
    private Payment openOrder() {
        Payment payment = new Payment();
        payment.setId(900L);
        payment.setLibrary(library);
        payment.setTransaction(loan);
        payment.setInitiatedBy(member);
        payment.setProvider("hmac-sandbox");
        payment.setProviderOrderId(ORDER);
        payment.setAmount(new BigDecimal("5.00"));
        payment.setCurrency("INR");
        payment.setStatus(PaymentStatus.CREATED);
        payment.setCreatedAt(LocalDateTime.now());
        when(paymentRepository.findByLibraryIdAndProviderOrderId(LIBRARY_ID, ORDER))
                .thenReturn(Optional.of(payment));
        return payment;
    }

    private static PaymentVerificationRequest verification() {
        return new PaymentVerificationRequest(ORDER, PAYMENT_REF, SIGNATURE);
    }

    private void signatureIs(boolean valid) {
        when(gateway.verify(anyString(), anyString(), anyString())).thenReturn(valid);
    }

    // ---------- opening an order ----------

    @Test
    void anOrderIsOpenedForTheOutstandingFine() {
        PaymentOrderResponse response = service.createOrder(LOAN_ID, "member");

        assertThat(response.providerOrderId()).isEqualTo(ORDER);
        assertThat(response.amount()).isEqualByComparingTo("5.00");
        assertThat(response.currency()).isEqualTo("INR");
        assertThat(response.loanId()).isEqualTo(LOAN_ID);
    }

    @Test
    void askingTwiceReturnsTheOrderThatIsAlreadyOpen() {
        Payment existing = openOrder();
        when(paymentRepository.findFirstByTransactionIdAndStatusOrderByIdDesc(LOAN_ID, PaymentStatus.CREATED))
                .thenReturn(Optional.of(existing));

        PaymentOrderResponse response = service.createOrder(LOAN_ID, "member");

        assertThat(response.providerOrderId()).isEqualTo(ORDER);
        verify(paymentRepository, never()).save(any(Payment.class));
        verify(gateway, never()).createOrder(anyString(), any(), anyString());
    }

    @Test
    void staffMayOpenAnOrderForAMembersFine() {
        assertThatCode(() -> service.createOrder(LOAN_ID, "librarian")).doesNotThrowAnyException();
    }

    @Test
    void aMemberMayNotOpenAnOrderForSomeoneElsesFine() {
        loan.setUser(otherMember);

        assertThatThrownBy(() -> service.createOrder(LOAN_ID, "member"))
                .isInstanceOf(TransactionAccessDeniedException.class);
    }

    @Test
    void aLoanOfAnotherLibraryIsNotFound() {
        when(transactionRepository.findByIdAndLibraryId(999L, LIBRARY_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createOrder(999L, "member"))
                .isInstanceOf(TransactionNotFoundException.class);
    }

    @Test
    void anAlreadyPaidFineOpensNoOrder() {
        loan.setFinePaymentStatus(FinePaymentStatus.PAID);

        assertThatThrownBy(() -> service.createOrder(LOAN_ID, "member"))
                .isInstanceOf(FinePaymentNotAllowedException.class);
        verify(paymentRepository, never()).save(any(Payment.class));
    }

    @Test
    void aBookStillOutOrAFineOfNothingOpensNoOrder() {
        loan.setReturnDate(null);
        assertThatThrownBy(() -> service.createOrder(LOAN_ID, "member"))
                .isInstanceOf(FinePaymentNotAllowedException.class);

        loan.setReturnDate(LocalDate.now());
        loan.setFinePaymentStatus(FinePaymentStatus.NOT_REQUIRED);
        assertThatThrownBy(() -> service.createOrder(LOAN_ID, "member"))
                .isInstanceOf(FinePaymentNotAllowedException.class);
    }

    @Test
    void noOrderIsOpenedWhereThereIsNoGatewayToVerifyItWith() {
        when(gateway.configured()).thenReturn(false);

        assertThatThrownBy(() -> service.createOrder(LOAN_ID, "member"))
                .isInstanceOf(PaymentGatewayNotConfiguredException.class);
        verify(paymentRepository, never()).save(any(Payment.class));
    }

    // ---------- verifying ----------

    @Test
    void averifiedPaymentSettlesTheFineAndIsAudited() {
        openOrder();
        signatureIs(true);

        PaymentResponse response = service.verifyAndSettle(LOAN_ID, verification(), "member");

        assertThat(response.status()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(response.providerPaymentId()).isEqualTo(PAYMENT_REF);
        assertThat(loan.getFinePaymentStatus()).isEqualTo(FinePaymentStatus.PAID);
        assertThat(loan.getFinePaidAt()).isNotNull();
        assertThat(loan.getFinePaymentRecordedBy()).isEqualTo(member);
        verify(transactionRepository).save(loan);
        verify(auditService).recordSuccess(eq(AuditAction.FINE_PAID), eq(LIBRARY_ID), eq(member.getId()), any());
    }

    @Test
    void aSignatureThatDoesNotVerifyLeavesTheFineAloneAndIsRecordedAsAFailure() {
        Payment payment = openOrder();
        signatureIs(false);

        assertThatThrownBy(() -> service.verifyAndSettle(LOAN_ID, verification(), "member"))
                .isInstanceOf(PaymentVerificationFailedException.class);

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(loan.getFinePaymentStatus()).as("untouched").isEqualTo(FinePaymentStatus.UNPAID);
        verify(auditService).recordFailure(eq(AuditAction.FINE_PAID), eq(LIBRARY_ID), eq(member.getId()), any());
        verify(auditService, never()).recordSuccess(any(), anyLong(), anyLong(), any());
    }

    @Test
    void anOrderOfAnotherLibraryReadsAsAFailedVerification() {
        when(paymentRepository.findByLibraryIdAndProviderOrderId(LIBRARY_ID, ORDER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.verifyAndSettle(LOAN_ID, verification(), "member"))
                .as("not a 404 - it says nothing about whether the order exists")
                .isInstanceOf(PaymentVerificationFailedException.class);
    }

    @Test
    void anOrderBelongingToAnotherLoanIsRefused() {
        openOrder();
        signatureIs(true);

        assertThatThrownBy(() -> service.verifyAndSettle(999L, verification(), "member"))
                .isInstanceOf(PaymentVerificationFailedException.class);
        assertThat(loan.getFinePaymentStatus()).isEqualTo(FinePaymentStatus.UNPAID);
    }

    @Test
    void aMemberMayNotVerifySomeoneElsesPayment() {
        openOrder();
        loan.setUser(otherMember);
        signatureIs(true);

        assertThatThrownBy(() -> service.verifyAndSettle(LOAN_ID, verification(), "member"))
                .isInstanceOf(TransactionAccessDeniedException.class);
        assertThat(loan.getFinePaymentStatus()).isEqualTo(FinePaymentStatus.UNPAID);
    }

    // ---------- twice ----------

    @Test
    void theSameVerifiedPaymentSentAgainChangesNothing() {
        Payment payment = openOrder();
        signatureIs(true);

        service.verifyAndSettle(LOAN_ID, verification(), "member");
        LocalDateTime firstPaidAt = loan.getFinePaidAt();

        PaymentResponse again = service.verifyAndSettle(LOAN_ID, verification(), "member");

        assertThat(again.status()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(loan.getFinePaidAt()).as("not settled a second time").isEqualTo(firstPaidAt);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        verify(auditService).recordSuccess(any(), anyLong(), anyLong(), any());
        verify(transactionRepository).save(loan);
    }

    @Test
    void aDifferentPaymentAgainstAnOrderThatAlreadySucceededIsRefused() {
        openOrder();
        signatureIs(true);
        service.verifyAndSettle(LOAN_ID, verification(), "member");

        PaymentVerificationRequest other = new PaymentVerificationRequest(ORDER, "pay_someone_else", SIGNATURE);

        assertThatThrownBy(() -> service.verifyAndSettle(LOAN_ID, other, "member"))
                .isInstanceOf(PaymentVerificationFailedException.class);
    }

    @Test
    void aVerifiedPaymentForAFineSettledAtTheDeskMeanwhileIsRefused() {
        openOrder();
        signatureIs(true);
        loan.setFinePaymentStatus(FinePaymentStatus.PAID);

        assertThatThrownBy(() -> service.verifyAndSettle(LOAN_ID, verification(), "member"))
                .as("the desk got there first; this must not pay it twice")
                .isInstanceOf(FinePaymentNotAllowedException.class);
        verify(auditService, never()).recordSuccess(any(), anyLong(), anyLong(), any());
    }

    @Test
    void aSecondSucceededPaymentForTheSameLoanIsRefused() {
        openOrder();
        signatureIs(true);
        when(paymentRepository.existsByTransactionIdAndStatus(LOAN_ID, PaymentStatus.SUCCEEDED)).thenReturn(true);

        assertThatThrownBy(() -> service.verifyAndSettle(LOAN_ID, verification(), "member"))
                .isInstanceOf(FinePaymentNotAllowedException.class);
        assertThat(loan.getFinePaymentStatus()).isEqualTo(FinePaymentStatus.UNPAID);
    }

    @Test
    void aFailedAttemptCannotBeRevived() {
        Payment payment = openOrder();
        payment.setStatus(PaymentStatus.FAILED);
        signatureIs(true);

        assertThatThrownBy(() -> service.verifyAndSettle(LOAN_ID, verification(), "member"))
                .isInstanceOf(PaymentVerificationFailedException.class);
        assertThat(loan.getFinePaymentStatus()).isEqualTo(FinePaymentStatus.UNPAID);
    }
}
