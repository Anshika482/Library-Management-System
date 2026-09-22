package com.library.lms.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import com.library.lms.dto.TransactionResponse;
import com.library.lms.entity.Book;
import com.library.lms.entity.Library;
import com.library.lms.entity.Role;
import com.library.lms.entity.Transaction;
import com.library.lms.entity.TransactionStatus;
import com.library.lms.entity.User;
import com.library.lms.exception.ReturnBookNotAllowedException;
import com.library.lms.repository.BookRepository;
import com.library.lms.repository.TransactionRepository;
import com.library.lms.repository.UserRepository;

/**
 * Guards the pairing of a loan's status and its return date.
 *
 * <p>Nothing in the database enforces this relationship - {@code return_date} is
 * nullable, as it must be for a loan that is still out, and {@code transactions}
 * carries no CHECK constraint. The invariant is upheld entirely by two adjacent
 * lines in {@code returnBook} and by the status guard that stops a loan being
 * closed twice. Adjacent lines are exactly the kind of thing a later refactor
 * separates without noticing, which is what these tests exist to prevent.</p>
 *
 * <p>Two invariants are pinned here:</p>
 * <ul>
 *   <li>a RETURNED loan always has a return date, set to the day it came back;</li>
 *   <li>a loan already RETURNED cannot be returned again, and a refused attempt
 *       changes nothing at all.</li>
 * </ul>
 *
 * <p>Every repository is mocked, so no Spring context starts and no row is read
 * or written; the overdue rules are the real ones. Every loan here is due in the
 * future, so each return owes nothing - fines are covered by
 * {@code OverduePolicyTest} and {@code OverdueFineIntegrationTest}.</p>
 */
@ExtendWith(MockitoExtension.class)
class TransactionServiceReturnBookTest {

    private static final Long LIBRARY_ID = 1L;

    private static final String CALLER = "a-librarian";

    private static final Long TRANSACTION_ID = 500L;

    private static final Long BOOK_ID = 42L;

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private BookRepository bookRepository;

    @Mock
    private UserRepository userRepository;

    /** The real overdue rules at 1.00 a day, on the system clock the fixtures' dates are built from. */
    @Spy
    private OverduePolicy overduePolicy = new OverduePolicy("1.00");

    /** The audit boundary: what it was told is asserted where it matters, and ignored elsewhere. */
    @Mock
    private AuditService auditService;

    @InjectMocks
    private TransactionService transactionService;

    // ---------- fixtures ----------

    private static Library library() {
        Library library = new Library();
        library.setId(LIBRARY_ID);
        return library;
    }

    private static User caller() {
        User user = new User();
        user.setId(7L);
        user.setUsername(CALLER);
        user.setRole(Role.ROLE_LIBRARIAN);
        user.setLibrary(library());
        return user;
    }

    private static Book book(int totalCopies, int availableCopies) {
        Book book = new Book();
        book.setId(BOOK_ID);
        book.setTitle("A Book");
        book.setTotalCopies(totalCopies);
        book.setAvailableCopies(availableCopies);
        book.setLibrary(library());
        return book;
    }

    private static Transaction loan(Book book, TransactionStatus status, LocalDate returnDate) {
        Transaction transaction = new Transaction();
        transaction.setId(TRANSACTION_ID);
        transaction.setBook(book);
        transaction.setUser(caller());
        transaction.setLibrary(library());
        transaction.setIssueDate(LocalDate.now().minusDays(10));
        transaction.setDueDate(LocalDate.now().plusDays(4));
        transaction.setStatus(status);
        transaction.setReturnDate(returnDate);
        return transaction;
    }

    private void callerIsKnown() {
        when(userRepository.findByUsername(CALLER)).thenReturn(Optional.of(caller()));
    }

    private void loanOnFile(Transaction transaction) {
        when(transactionRepository.findByIdAndLibraryId(TRANSACTION_ID, LIBRARY_ID))
                .thenReturn(Optional.of(transaction));
    }

    // ---------- 1. the double-return guard ----------

    @Test
    void returningAnAlreadyReturnedLoanIsRejected() {
        callerIsKnown();
        loanOnFile(loan(book(5, 3), TransactionStatus.RETURNED, LocalDate.now().minusDays(2)));

        assertThatThrownBy(() -> transactionService.returnBook(TRANSACTION_ID, CALLER))
                .isInstanceOf(ReturnBookNotAllowedException.class);
    }

    @Test
    void aRefusedSecondReturnSavesNothing() {
        callerIsKnown();
        loanOnFile(loan(book(5, 3), TransactionStatus.RETURNED, LocalDate.now().minusDays(2)));

        assertThatThrownBy(() -> transactionService.returnBook(TRANSACTION_ID, CALLER))
                .isInstanceOf(ReturnBookNotAllowedException.class);

        verify(transactionRepository, never()).save(any(Transaction.class));
        verify(bookRepository, never()).save(any(Book.class));
    }

    @Test
    void aRefusedSecondReturnLeavesTheLoanExactlyAsItWas() {
        LocalDate originalReturnDate = LocalDate.now().minusDays(2);
        Transaction alreadyClosed = loan(book(5, 3), TransactionStatus.RETURNED, originalReturnDate);
        callerIsKnown();
        loanOnFile(alreadyClosed);

        assertThatThrownBy(() -> transactionService.returnBook(TRANSACTION_ID, CALLER))
                .isInstanceOf(ReturnBookNotAllowedException.class);

        assertThat(alreadyClosed.getStatus())
                .as("the loan stays closed")
                .isEqualTo(TransactionStatus.RETURNED);
        assertThat(alreadyClosed.getReturnDate())
                .as("the original return date is not overwritten with today's")
                .isEqualTo(originalReturnDate);
    }

    @Test
    void aRefusedSecondReturnDoesNotRestockTheBook() {
        // The damage a missing guard would do is not just a wrong date: every
        // repeat return would put another copy on the shelf that nobody owns.
        Book book = book(5, 3);
        callerIsKnown();
        loanOnFile(loan(book, TransactionStatus.RETURNED, LocalDate.now().minusDays(2)));

        assertThatThrownBy(() -> transactionService.returnBook(TRANSACTION_ID, CALLER))
                .isInstanceOf(ReturnBookNotAllowedException.class);

        assertThat(book.getAvailableCopies()).isEqualTo(3);
        assertThat(book.getTotalCopies()).isEqualTo(5);
    }

    // ---------- 2. RETURNED implies a return date ----------

    @Test
    void aSuccessfulReturnSetsStatusAndReturnDateTogether() {
        callerIsKnown();
        loanOnFile(loan(book(5, 2), TransactionStatus.ISSUED, null));
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(i -> i.getArgument(0));

        TransactionResponse response = transactionService.returnBook(TRANSACTION_ID, CALLER);

        // Stated as one relationship rather than two unrelated assertions: a
        // RETURNED loan without a date is the state that must never exist.
        assertThat(response.getStatus()).isEqualTo(TransactionStatus.RETURNED);
        assertThat(response.getReturnDate()).isNotNull();
    }

    @Test
    void theReturnDateIsTheDayTheLoanWasClosed() {
        callerIsKnown();
        loanOnFile(loan(book(5, 2), TransactionStatus.ISSUED, null));
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(i -> i.getArgument(0));

        transactionService.returnBook(TRANSACTION_ID, CALLER);

        ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository, times(1)).save(captor.capture());
        Transaction saved = captor.getValue();

        assertThat(saved.getStatus()).isEqualTo(TransactionStatus.RETURNED);
        assertThat(saved.getReturnDate())
                .as("the date of the return operation, not the issue or due date")
                .isEqualTo(LocalDate.now());
        assertThat(saved.getReturnDate()).isNotEqualTo(saved.getIssueDate());
    }

    @Test
    void theSavedLoanNeverCarriesReturnedWithoutADate() {
        // The invariant asserted directly on the row that reaches the
        // repository, which is the only thing the database will ever see.
        callerIsKnown();
        loanOnFile(loan(book(5, 2), TransactionStatus.ISSUED, null));
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(i -> i.getArgument(0));

        transactionService.returnBook(TRANSACTION_ID, CALLER);

        ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository).save(captor.capture());
        Transaction saved = captor.getValue();

        boolean returnedWithoutDate =
                saved.getStatus() == TransactionStatus.RETURNED && saved.getReturnDate() == null;
        assertThat(returnedWithoutDate)
                .as("RETURNED with a null return date must never be written")
                .isFalse();
    }
}
