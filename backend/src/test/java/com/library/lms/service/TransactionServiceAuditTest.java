package com.library.lms.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.library.lms.entity.AuditAction;
import com.library.lms.entity.AuditOutcome;
import com.library.lms.entity.AuditTargetType;
import com.library.lms.entity.Book;
import com.library.lms.entity.FinePaymentStatus;
import com.library.lms.entity.Library;
import com.library.lms.entity.Role;
import com.library.lms.entity.Transaction;
import com.library.lms.entity.TransactionStatus;
import com.library.lms.entity.User;
import com.library.lms.exception.BookNotAvailableException;
import com.library.lms.exception.BookNotFoundException;
import com.library.lms.exception.FinePaymentNotAllowedException;
import com.library.lms.exception.InvalidDueDateException;
import com.library.lms.exception.MemberNotEligibleException;
import com.library.lms.exception.ReturnBookNotAllowedException;
import com.library.lms.exception.TransactionAccessDeniedException;
import com.library.lms.exception.TransactionNotFoundException;
import com.library.lms.exception.UserNotFoundException;
import com.library.lms.repository.BookRepository;
import com.library.lms.repository.TransactionRepository;
import com.library.lms.repository.UserRepository;

/**
 * What the three loan operations record: a book issued, a book returned, a fine
 * recorded as paid, and every refusal of each.
 *
 * <p><b>The actor is the member of staff</b> who performed the operation, never
 * the borrower - the borrower is on the loan row the event points at. The
 * library is the staff account's own, and the target is the loan, by the
 * transaction id.</p>
 *
 * <p><b>A refusal before there is a loan carries no target.</b> Issuing can be
 * refused for a book, a member or a copy that is not there, and no loan exists
 * to name; returning and paying are always about a loan the caller named, so
 * those refusals carry that id even when it belongs to nothing.</p>
 *
 * <p>No database and no Spring context: the audit boundary is a mock, so what
 * it was told can be read argument by argument. {@code AuditLoggingIntegration
 * Test} covers the transactions these calls run in.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TransactionServiceAuditTest {

    private static final long LIBRARY_ID = 7L;

    private static final long STAFF_ID = 11L;

    private static final long LOAN_ID = 4242L;

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private BookRepository bookRepository;

    @Mock
    private UserRepository userRepository;

    /** The real overdue rules at 1.00 a day, as the other loan tests use. */
    @Spy
    private OverduePolicy overduePolicy = new OverduePolicy("1.00");

    @Mock
    private AuditService auditService;

    @InjectMocks
    private TransactionService transactionService;

    private Library library;
    private User staff;
    private User member;
    private Book book;

    @BeforeEach
    void fixtures() {
        library = new Library();
        library.setId(LIBRARY_ID);

        staff = account(STAFF_ID, "librarian", Role.ROLE_LIBRARIAN);
        member = account(21L, "member", Role.ROLE_MEMBER);

        book = new Book();
        book.setId(31L);
        book.setTitle("A Book");
        book.setTotalCopies(2);
        book.setAvailableCopies(1);
        book.setLibrary(library);

        when(userRepository.findByUsername("librarian")).thenReturn(Optional.of(staff));
        when(userRepository.findByUsername("member")).thenReturn(Optional.of(member));
        when(bookRepository.findByIdAndLibraryId(31L, LIBRARY_ID)).thenReturn(Optional.of(book));
        when(userRepository.findByIdAndLibraryId(21L, LIBRARY_ID)).thenReturn(Optional.of(member));
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(call -> {
            Transaction saved = call.getArgument(0);
            if (saved.getId() == null) {
                saved.setId(LOAN_ID);
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
        user.setEnabled(true);
        user.setAccountNonLocked(true);
        return user;
    }

    /** A returned loan with a fine still owed - what a payment is recorded against. */
    private Transaction returnedLoanOwing() {
        Transaction loan = openLoan();
        loan.setStatus(TransactionStatus.RETURNED);
        loan.setReturnDate(LocalDate.now());
        loan.setFineAmount(3.0);
        loan.setFinePaymentStatus(FinePaymentStatus.UNPAID);
        return loan;
    }

    private Transaction openLoan() {
        Transaction loan = new Transaction();
        loan.setId(LOAN_ID);
        loan.setBook(book);
        loan.setUser(member);
        loan.setLibrary(library);
        loan.setIssueDate(LocalDate.now().minusDays(3));
        loan.setDueDate(LocalDate.now().plusDays(4));
        loan.setStatus(TransactionStatus.ISSUED);
        return loan;
    }

    private void loanExists(Transaction loan) {
        when(transactionRepository.findByIdAndLibraryId(LOAN_ID, LIBRARY_ID)).thenReturn(Optional.of(loan));
    }

    private void noSuchLoan() {
        when(transactionRepository.findByIdAndLibraryId(LOAN_ID, LIBRARY_ID)).thenReturn(Optional.empty());
    }

    /** The one success the audit boundary was told about. */
    private AuditTarget recordedSuccess(AuditAction action) {
        ArgumentCaptor<AuditTarget> target = ArgumentCaptor.forClass(AuditTarget.class);
        verify(auditService).recordSuccess(eq(action), eq(LIBRARY_ID), eq(STAFF_ID), target.capture());
        verify(auditService, never()).recordFailure(any(), any(), any(), any());
        return target.getValue();
    }

    /** The one refusal the audit boundary was told about. */
    private AuditTarget recordedFailure(AuditAction action) {
        ArgumentCaptor<AuditTarget> target = ArgumentCaptor.forClass(AuditTarget.class);
        verify(auditService).recordFailure(eq(action), eq(LIBRARY_ID), eq(STAFF_ID), target.capture());
        verify(auditService, never()).recordSuccess(any(), any(), any(), any());
        return target.getValue();
    }

    // ---------- issuing ----------

    @Test
    void issuingABookRecordsItAgainstTheNewLoan() {
        transactionService.issueBook(31L, 21L, "librarian", LocalDate.now().plusDays(7));

        AuditTarget target = recordedSuccess(AuditAction.BOOK_ISSUED);
        assertThat(target.type()).isEqualTo(AuditTargetType.LOAN);
        assertThat(target.id()).as("the loan just written, not the book or the borrower").isEqualTo(LOAN_ID);
    }

    @Test
    void aBookThatIsNotInTheCallersLibraryIsRecordedAsARefusal() {
        when(bookRepository.findByIdAndLibraryId(99L, LIBRARY_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> transactionService.issueBook(99L, 21L, "librarian", LocalDate.now().plusDays(7)))
                .isInstanceOf(BookNotFoundException.class);

        assertThat(recordedFailure(AuditAction.BOOK_ISSUED).type())
                .as("no loan exists to name")
                .isNull();
    }

    @Test
    void aMemberThatIsNotInTheCallersLibraryIsRecordedAsARefusal() {
        when(userRepository.findByIdAndLibraryId(99L, LIBRARY_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> transactionService.issueBook(31L, 99L, "librarian", LocalDate.now().plusDays(7)))
                .isInstanceOf(UserNotFoundException.class);

        assertThat(recordedFailure(AuditAction.BOOK_ISSUED).id()).isNull();
    }

    @Test
    void anIneligibleBorrowerIsRecordedAsARefusal() {
        member.setEnabled(false);

        assertThatThrownBy(() -> transactionService.issueBook(31L, 21L, "librarian", LocalDate.now().plusDays(7)))
                .isInstanceOf(MemberNotEligibleException.class);

        recordedFailure(AuditAction.BOOK_ISSUED);
    }

    @Test
    void aBookWithNoCopyLeftIsRecordedAsARefusal() {
        book.setAvailableCopies(0);

        assertThatThrownBy(() -> transactionService.issueBook(31L, 21L, "librarian", LocalDate.now().plusDays(7)))
                .isInstanceOf(BookNotAvailableException.class);

        recordedFailure(AuditAction.BOOK_ISSUED);
    }

    @Test
    void aDueDateRefusedBeforeAnyAccountIsReadRecordsNothing() {
        assertThatThrownBy(() -> transactionService.issueBook(31L, 21L, "librarian", LocalDate.now().minusDays(1)))
                .isInstanceOf(InvalidDueDateException.class);

        // There is no library yet to record it in - the same rule the account
        // actions follow for an address that matches no account.
        verify(auditService, never()).recordFailure(any(), any(), any(), any());
        verify(auditService, never()).recordSuccess(any(), any(), any(), any());
    }

    // ---------- returning ----------

    @Test
    void returningABookRecordsItAgainstTheLoan() {
        loanExists(openLoan());

        transactionService.returnBook(LOAN_ID, "librarian");

        AuditTarget target = recordedSuccess(AuditAction.BOOK_RETURNED);
        assertThat(target.type()).isEqualTo(AuditTargetType.LOAN);
        assertThat(target.id()).isEqualTo(LOAN_ID);
    }

    @Test
    void returningALoanThatIsNotThereIsRecordedAgainstTheIdThatWasNamed() {
        noSuchLoan();

        assertThatThrownBy(() -> transactionService.returnBook(LOAN_ID, "librarian"))
                .isInstanceOf(TransactionNotFoundException.class);

        AuditTarget target = recordedFailure(AuditAction.BOOK_RETURNED);
        assertThat(target.type()).isEqualTo(AuditTargetType.LOAN);
        assertThat(target.id()).as("what they tried to close, whether or not it exists here").isEqualTo(LOAN_ID);
    }

    @Test
    void returningALoanThatIsAlreadyClosedIsRecordedAsARefusal() {
        loanExists(returnedLoanOwing());

        assertThatThrownBy(() -> transactionService.returnBook(LOAN_ID, "librarian"))
                .isInstanceOf(ReturnBookNotAllowedException.class);

        assertThat(recordedFailure(AuditAction.BOOK_RETURNED).id()).isEqualTo(LOAN_ID);
    }

    @Test
    void returningACopyTheLibraryDoesNotOwnIsRecordedAsARefusal() {
        book.setAvailableCopies(2);
        book.setTotalCopies(2);
        loanExists(openLoan());

        assertThatThrownBy(() -> transactionService.returnBook(LOAN_ID, "librarian"))
                .isInstanceOf(ReturnBookNotAllowedException.class);

        assertThat(recordedFailure(AuditAction.BOOK_RETURNED).id()).isEqualTo(LOAN_ID);
    }

    // ---------- paying a fine ----------

    @Test
    void recordingAFinePaymentRecordsItAgainstTheLoan() {
        loanExists(returnedLoanOwing());

        transactionService.recordFinePayment(LOAN_ID, "librarian");

        AuditTarget target = recordedSuccess(AuditAction.FINE_PAID);
        assertThat(target.type()).isEqualTo(AuditTargetType.LOAN);
        assertThat(target.id()).isEqualTo(LOAN_ID);
    }

    @Test
    void theEventCarriesNoAmount() {
        Transaction loan = returnedLoanOwing();
        loan.setFineAmount(97.5);
        loanExists(loan);

        transactionService.recordFinePayment(LOAN_ID, "librarian");

        // The four arguments the boundary takes are an action, a library, an
        // actor and a target; there is nowhere for a figure to go, and this
        // pins that the loan is what the event points at instead.
        AuditTarget target = recordedSuccess(AuditAction.FINE_PAID);
        assertThat(target.id()).isEqualTo(LOAN_ID);
        assertThat(BigDecimal.valueOf(loan.getFineAmount())).isEqualByComparingTo("97.5");
    }

    @Test
    void aMemberTryingToClearTheirOwnFineIsRecordedAsARefusal() {
        assertThatThrownBy(() -> transactionService.recordFinePayment(LOAN_ID, "member"))
                .isInstanceOf(TransactionAccessDeniedException.class);

        ArgumentCaptor<AuditTarget> target = ArgumentCaptor.forClass(AuditTarget.class);
        verify(auditService).recordFailure(eq(AuditAction.FINE_PAID), eq(LIBRARY_ID), eq(member.getId()),
                target.capture());
        assertThat(target.getValue().id()).isEqualTo(LOAN_ID);
        verify(auditService, never()).recordSuccess(any(), any(), any(), any());
    }

    @Test
    void payingAFineOnALoanThatIsNotThereIsRecordedAgainstTheIdThatWasNamed() {
        noSuchLoan();

        assertThatThrownBy(() -> transactionService.recordFinePayment(LOAN_ID, "librarian"))
                .isInstanceOf(TransactionNotFoundException.class);

        assertThat(recordedFailure(AuditAction.FINE_PAID).id()).isEqualTo(LOAN_ID);
    }

    @Test
    void payingAFineWhileTheBookIsStillOutIsRecordedAsARefusal() {
        loanExists(openLoan());

        assertThatThrownBy(() -> transactionService.recordFinePayment(LOAN_ID, "librarian"))
                .isInstanceOf(FinePaymentNotAllowedException.class);

        assertThat(recordedFailure(AuditAction.FINE_PAID).id()).isEqualTo(LOAN_ID);
    }

    @Test
    void payingAFineTwiceIsRecordedAsARefusal() {
        Transaction loan = returnedLoanOwing();
        loan.setFinePaymentStatus(FinePaymentStatus.PAID);
        loanExists(loan);

        assertThatThrownBy(() -> transactionService.recordFinePayment(LOAN_ID, "librarian"))
                .isInstanceOf(FinePaymentNotAllowedException.class);

        assertThat(recordedFailure(AuditAction.FINE_PAID).id()).isEqualTo(LOAN_ID);
    }

    @Test
    void payingAFineNobodyOwesIsRecordedAsARefusal() {
        Transaction loan = returnedLoanOwing();
        loan.setFineAmount(0.0);
        loan.setFinePaymentStatus(FinePaymentStatus.NOT_REQUIRED);
        loanExists(loan);

        assertThatThrownBy(() -> transactionService.recordFinePayment(LOAN_ID, "librarian"))
                .isInstanceOf(FinePaymentNotAllowedException.class);

        assertThat(recordedFailure(AuditAction.FINE_PAID).id()).isEqualTo(LOAN_ID);
    }
}
