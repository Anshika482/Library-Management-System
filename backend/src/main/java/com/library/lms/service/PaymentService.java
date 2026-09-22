package com.library.lms.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.library.lms.dto.PaymentOrderResponse;
import com.library.lms.dto.PaymentResponse;
import com.library.lms.dto.PaymentVerificationRequest;
import com.library.lms.entity.AuditAction;
import com.library.lms.entity.FinePaymentStatus;
import com.library.lms.entity.Payment;
import com.library.lms.entity.PaymentStatus;
import com.library.lms.entity.Role;
import com.library.lms.entity.Transaction;
import com.library.lms.entity.User;
import com.library.lms.exception.FinePaymentNotAllowedException;
import com.library.lms.exception.PaymentGatewayNotConfiguredException;
import com.library.lms.exception.PaymentVerificationFailedException;
import com.library.lms.exception.TransactionAccessDeniedException;
import com.library.lms.exception.TransactionNotFoundException;
import com.library.lms.exception.UserNotFoundException;
import com.library.lms.repository.PaymentRepository;
import com.library.lms.repository.TransactionRepository;
import com.library.lms.repository.UserRepository;

/**
 * Paying a fine through a payment provider.
 *
 * <p><b>The fine moves only after the server has verified the provider's
 * answer.</b> A client that says a payment succeeded is not believed: the
 * signature is recomputed here, with a secret the client does not hold, over
 * the two references the provider returned. Only then is the fine marked paid,
 * and only then does a {@code FINE_PAID} audit event exist. Everything a caller
 * sends is otherwise treated as a claim.</p>
 *
 * <p><b>The desk flow is untouched.</b> {@code TransactionService.recordFinePayment}
 * is still how staff record a fine paid in cash, with the same rules and the
 * same answers. This class is a second way to the same end state, for the
 * member who owes the fine, and it enforces the same three conditions - the
 * book is back, the fine is owed, and it has not been paid - answering with the
 * same {@link FinePaymentNotAllowedException} so a client cannot tell the two
 * routes apart by their errors.</p>
 *
 * <p><b>Who may pay.</b> The member the loan belongs to, and the staff of the
 * library it belongs to. The filter chain requires a signed-in caller; the rule
 * about <i>whose</i> loan it is lives here, because only this layer knows. A
 * loan of another library is not found at all, exactly as elsewhere.</p>
 *
 * <p><b>Paying twice is impossible in three places.</b> The fine's own state
 * refuses a second payment; a loan that already has a succeeded payment refuses
 * another; and the provider's references are unique in the database, so even a
 * replayed request that got past both would fail to insert. A replay of the
 * <i>same</i> verified payment is answered with the same result and changes
 * nothing - no second fine payment, no second audit event.</p>
 *
 * <p><b>No card data reaches this class</b>, by construction: the provider
 * takes the card on its own pages, and the only fields here are its two
 * references and a signature.</p>
 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final PaymentRepository paymentRepository;

    private final TransactionRepository transactionRepository;

    private final UserRepository userRepository;

    private final PaymentGateway gateway;

    private final AuditService auditService;

    private final String currency;

    public PaymentService(PaymentRepository paymentRepository,
                          TransactionRepository transactionRepository,
                          UserRepository userRepository,
                          PaymentGateway gateway,
                          AuditService auditService,
                          @org.springframework.beans.factory.annotation.Value("${payment.currency:INR}")
                          String currency) {
        this.paymentRepository = paymentRepository;
        this.transactionRepository = transactionRepository;
        this.userRepository = userRepository;
        this.gateway = gateway;
        this.auditService = auditService;
        this.currency = currency == null || currency.isBlank() ? "INR" : currency.trim().toUpperCase(
                java.util.Locale.ROOT);
    }

    /**
     * Opens an order for a loan's outstanding fine, or hands back the one that
     * is already open.
     *
     * <p>Asking twice does not pile up orders: an order still awaiting payment
     * is returned as it stands, so a client that retries - or a member who
     * reloads the page - settles the same order rather than a second one.</p>
     *
     * @param loanId                the loan whose fine is to be paid
     * @param authenticatedUsername the caller, from the security context
     * @throws TransactionNotFoundException          if the caller's library has
     *                                               no loan with this id
     * @throws TransactionAccessDeniedException      if the loan is another
     *                                               member's and the caller is
     *                                               not staff
     * @throws FinePaymentNotAllowedException        if the book is still out,
     *                                               the fine is already paid, or
     *                                               nothing is owed
     * @throws PaymentGatewayNotConfiguredException  if this deployment has no
     *                                               gateway credentials
     */
    @Transactional
    public PaymentOrderResponse createOrder(Long loanId, String authenticatedUsername) {
        User caller = authenticatedUser(authenticatedUsername);
        Long libraryId = caller.getLibrary().getId();
        Transaction loan = payableLoan(loanId, libraryId, caller);

        if (!gateway.configured()) {
            // Refused before anything is written: an order nobody could verify
            // afterwards is worse than no order at all.
            throw new PaymentGatewayNotConfiguredException();
        }

        Optional<Payment> open = paymentRepository
                .findFirstByTransactionIdAndStatusOrderByIdDesc(loan.getId(), PaymentStatus.CREATED);
        if (open.isPresent()) {
            return toOrderResponse(open.get());
        }

        BigDecimal amount = BigDecimal.valueOf(loan.getFineAmount()).setScale(2, RoundingMode.HALF_UP);

        Payment payment = new Payment();
        payment.setLibrary(loan.getLibrary());
        payment.setTransaction(loan);
        payment.setInitiatedBy(caller);
        payment.setProvider(gateway.name());
        payment.setProviderOrderId(gateway.createOrder(String.valueOf(loan.getId()), amount, currency));
        payment.setAmount(amount);
        payment.setCurrency(currency);
        payment.setStatus(PaymentStatus.CREATED);
        payment.setCreatedAt(LocalDateTime.now());

        Payment saved = paymentRepository.save(payment);

        // Ids and an amount. Not the order reference beside the caller's name,
        // and never anything that could identify a card.
        log.info("Payment order opened for loan id={} by user id={} in library id={}",
                loan.getId(), caller.getId(), libraryId);

        return toOrderResponse(saved);
    }

    /**
     * Verifies the provider's answer and, only if it is genuine, settles the
     * fine.
     *
     * <p>The transaction deliberately commits when this throws
     * {@link PaymentVerificationFailedException}: the attempt is marked FAILED,
     * and rolling that back would leave no trace of an answer that did not
     * verify - which is the one attempt most worth keeping.</p>
     *
     * @param loanId                the loan the order belongs to
     * @param request               the provider's two references and its signature
     * @param authenticatedUsername the caller, from the security context
     * @throws PaymentVerificationFailedException if the signature is not the
     *                                            provider's over those
     *                                            references, or the order is not
     *                                            this loan's
     * @throws TransactionAccessDeniedException   if the loan is another member's
     *                                            and the caller is not staff
     */
    @Transactional(noRollbackFor = PaymentVerificationFailedException.class)
    public PaymentResponse verifyAndSettle(Long loanId, PaymentVerificationRequest request,
            String authenticatedUsername) {
        User caller = authenticatedUser(authenticatedUsername);
        Long libraryId = caller.getLibrary().getId();

        // An order of another library reads exactly like one that does not
        // exist: a failed verification, saying nothing either way.
        Payment payment = paymentRepository
                .findByLibraryIdAndProviderOrderId(libraryId, request.getProviderOrderId())
                .orElseThrow(PaymentVerificationFailedException::new);

        if (!payment.getTransaction().getId().equals(loanId)) {
            throw new PaymentVerificationFailedException();
        }

        Transaction loan = payment.getTransaction();
        requireOwnLoanOrStaff(loan, caller);

        // A replay of the payment that already succeeded: the same answer, and
        // nothing happens a second time - no second settlement, no second event.
        if (payment.getStatus() == PaymentStatus.SUCCEEDED) {
            if (!request.getProviderPaymentId().equals(payment.getProviderPaymentId())
                    || !gateway.verify(payment.getProviderOrderId(), request.getProviderPaymentId(),
                            request.getSignature())) {
                throw new PaymentVerificationFailedException();
            }
            return toResponse(payment, loan);
        }

        if (payment.getStatus() != PaymentStatus.CREATED) {
            throw new PaymentVerificationFailedException();
        }

        if (!gateway.verify(payment.getProviderOrderId(), request.getProviderPaymentId(), request.getSignature())) {
            payment.setStatus(PaymentStatus.FAILED);
            payment.setCompletedAt(LocalDateTime.now());
            paymentRepository.save(payment);

            auditService.recordFailure(AuditAction.FINE_PAID, libraryId, caller.getId(),
                    AuditTarget.loan(loan.getId()));

            log.warn("Payment verification failed for loan id={} in library id={}", loan.getId(), libraryId);

            throw new PaymentVerificationFailedException();
        }

        // Verified. Only now are the fine's own rules applied: a fine that was
        // settled at the desk in the meantime must not be paid a second time.
        requireOutstandingFine(loan);
        if (paymentRepository.existsByTransactionIdAndStatus(loan.getId(), PaymentStatus.SUCCEEDED)) {
            throw FinePaymentNotAllowedException.alreadyPaid();
        }

        LocalDateTime paidAt = LocalDateTime.now();

        payment.setStatus(PaymentStatus.SUCCEEDED);
        payment.setProviderPaymentId(request.getProviderPaymentId());
        payment.setCompletedAt(paidAt);
        paymentRepository.save(payment);

        // The same three fields the desk flow sets, so a fine paid online and
        // one paid in cash are the same thing afterwards. The payer is who
        // recorded it, which for an online payment is the member themselves.
        loan.setFinePaymentStatus(FinePaymentStatus.PAID);
        loan.setFinePaidAt(paidAt);
        loan.setFinePaymentRecordedBy(caller);
        transactionRepository.save(loan);

        // The existing audit action, in this transaction, so the payment and the
        // record of it commit together. No amount, no reference - the loan and
        // the payment row hold those.
        auditService.recordSuccess(AuditAction.FINE_PAID, libraryId, caller.getId(),
                AuditTarget.loan(loan.getId()));

        log.info("Fine paid online for loan id={} by user id={} in library id={}",
                loan.getId(), caller.getId(), libraryId);

        return toResponse(payment, loan);
    }

    /** The loan, if the caller may pay it and its fine is outstanding. */
    private Transaction payableLoan(Long loanId, Long libraryId, User caller) {
        Transaction loan = transactionRepository.findByIdAndLibraryId(loanId, libraryId)
                .orElseThrow(() -> new TransactionNotFoundException(loanId));

        requireOwnLoanOrStaff(loan, caller);
        requireOutstandingFine(loan);

        return loan;
    }

    /**
     * The member whose loan it is, or staff of that library. A member paying
     * someone else's fine is refused before the loan is read any further.
     */
    private static void requireOwnLoanOrStaff(Transaction loan, User caller) {
        boolean staff = caller.getRole() == Role.ROLE_ADMIN || caller.getRole() == Role.ROLE_LIBRARIAN;
        boolean ownLoan = loan.getUser() != null && loan.getUser().getId().equals(caller.getId());

        if (!staff && !ownLoan) {
            throw new TransactionAccessDeniedException();
        }
    }

    /**
     * The same three conditions the desk flow applies, with the same errors: the
     * book is back, something is owed, and it has not been paid.
     */
    private static void requireOutstandingFine(Transaction loan) {
        if (loan.getReturnDate() == null || loan.getFineAmount() == null) {
            throw FinePaymentNotAllowedException.bookStillOut();
        }

        FinePaymentStatus current = loan.getFinePaymentStatus() != null
                ? loan.getFinePaymentStatus()
                : (loan.getFineAmount() > 0 ? FinePaymentStatus.UNPAID : FinePaymentStatus.NOT_REQUIRED);

        if (current == FinePaymentStatus.PAID) {
            throw FinePaymentNotAllowedException.alreadyPaid();
        }
        if (current != FinePaymentStatus.UNPAID) {
            throw FinePaymentNotAllowedException.nothingOwed();
        }
    }

    private User authenticatedUser(String authenticatedUsername) {
        return userRepository.findByUsername(authenticatedUsername)
                .orElseThrow(() -> new UserNotFoundException(authenticatedUsername));
    }

    /** The key id goes out because the checkout needs it; the secret has no route here. */
    private PaymentOrderResponse toOrderResponse(Payment payment) {
        return new PaymentOrderResponse(payment.getId(), payment.getProvider(), payment.getProviderOrderId(),
                gateway.keyId(), payment.getAmount(), payment.getCurrency(), payment.getTransaction().getId());
    }

    private static PaymentResponse toResponse(Payment payment, Transaction loan) {
        return new PaymentResponse(payment.getId(), payment.getStatus(), payment.getProviderPaymentId(),
                loan.getId(), loan.getFinePaymentStatus(), loan.getFinePaidAt());
    }
}
