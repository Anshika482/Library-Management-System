package com.library.lms.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.library.lms.dto.IssueBookRequest;
import com.library.lms.dto.TransactionResponse;
import com.library.lms.entity.Book;
import com.library.lms.entity.Library;
import com.library.lms.entity.Role;
import com.library.lms.entity.Transaction;
import com.library.lms.entity.TransactionStatus;
import com.library.lms.entity.User;
import com.library.lms.exception.BookNotAvailableException;
import com.library.lms.exception.BookNotFoundException;
import com.library.lms.exception.GlobalExceptionHandler;
import com.library.lms.exception.InvalidDueDateException;
import com.library.lms.exception.UserNotFoundException;
import com.library.lms.repository.BookRepository;
import com.library.lms.repository.TransactionRepository;
import com.library.lms.repository.UserRepository;

/**
 * Guards the rule that a caller cannot choose who borrows a book.
 *
 * <p>Every collaborator is mocked, so no Spring context starts and no row is
 * read or written. The assertions are about <i>where</i> the borrower comes
 * from, which is the point of the change they protect: the service must resolve
 * the account from the authenticated login name it was handed, and must never
 * look a user up by an id, because an id is the kind of thing a request body can
 * carry.</p>
 */
@ExtendWith(MockitoExtension.class)
class TransactionServiceIssueBookTest {

    private static final String AUTHENTICATED_USERNAME = "authenticated-member";

    private static final Long BOOK_ID = 7L;

    /** The caller's own library. Every fixture below belongs to it. */
    private static final Long LIBRARY_ID = 1L;

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private BookRepository bookRepository;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private TransactionService transactionService;

    private static Library library(Long id) {
        Library library = new Library();
        library.setId(id);
        return library;
    }

    private static Book availableBook(int copies) {
        Book book = new Book();
        book.setId(BOOK_ID);
        book.setTitle("A Book");
        book.setTotalCopies(copies);
        book.setAvailableCopies(copies);
        book.setLibrary(library(LIBRARY_ID));
        return book;
    }

    private static User borrower() {
        User user = new User();
        user.setId(42L);
        user.setUsername(AUTHENTICATED_USERNAME);
        user.setRole(Role.ROLE_MEMBER);
        user.setLibrary(library(LIBRARY_ID));
        return user;
    }

    /** Hands back whatever the service asked to save, with an id filled in. */
    private void echoSavedTransaction() {
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(invocation -> {
            Transaction saved = invocation.getArgument(0);
            saved.setId(1001L);
            return saved;
        });
    }

    @Test
    void issueBookRequestCarriesNoUserId() {
        assertThat(Arrays.stream(IssueBookRequest.class.getDeclaredFields()).map(Field::getName))
                .as("a borrower id must not be accepted from the client")
                .doesNotContain("userId")
                .containsExactlyInAnyOrder("bookId", "dueDate");

        assertThat(Arrays.stream(IssueBookRequest.class.getMethods()).map(Method::getName))
                .doesNotContain("getUserId", "setUserId");
    }

    @Test
    void resolvesTheBorrowerFromTheAuthenticatedUsername() {
        when(bookRepository.findByIdAndLibraryId(BOOK_ID, LIBRARY_ID)).thenReturn(Optional.of(availableBook(3)));
        when(userRepository.findByUsername(AUTHENTICATED_USERNAME)).thenReturn(Optional.of(borrower()));
        echoSavedTransaction();

        transactionService.issueBook(BOOK_ID, AUTHENTICATED_USERNAME, LocalDate.now().plusDays(14));

        verify(userRepository).findByUsername(AUTHENTICATED_USERNAME);
    }

    @Test
    void neverLooksTheBorrowerUpById() {
        when(bookRepository.findByIdAndLibraryId(BOOK_ID, LIBRARY_ID)).thenReturn(Optional.of(availableBook(3)));
        when(userRepository.findByUsername(AUTHENTICATED_USERNAME)).thenReturn(Optional.of(borrower()));
        echoSavedTransaction();

        transactionService.issueBook(BOOK_ID, AUTHENTICATED_USERNAME, LocalDate.now().plusDays(14));

        verify(userRepository, never()).findById(anyLong());
    }

    @Test
    void savesTheTransactionAgainstTheResolvedUser() {
        User resolved = borrower();
        when(bookRepository.findByIdAndLibraryId(BOOK_ID, LIBRARY_ID)).thenReturn(Optional.of(availableBook(3)));
        when(userRepository.findByUsername(AUTHENTICATED_USERNAME)).thenReturn(Optional.of(resolved));
        echoSavedTransaction();

        LocalDate dueDate = LocalDate.now().plusDays(14);
        transactionService.issueBook(BOOK_ID, AUTHENTICATED_USERNAME, dueDate);

        ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository).save(captor.capture());
        Transaction saved = captor.getValue();

        assertThat(saved.getUser()).isSameAs(resolved);
        assertThat(saved.getUser().getUsername()).isEqualTo(AUTHENTICATED_USERNAME);
        assertThat(saved.getBook().getId()).isEqualTo(BOOK_ID);
        assertThat(saved.getDueDate()).isEqualTo(dueDate);
        assertThat(saved.getIssueDate()).isEqualTo(LocalDate.now());
        assertThat(saved.getStatus()).isEqualTo(TransactionStatus.ISSUED);
        assertThat(saved.getReturnDate()).isNull();
        assertThat(saved.getFineAmount()).isNull();
    }

    @Test
    void decrementsAvailableCopiesAndLeavesTotalAlone() {
        Book book = availableBook(3);
        when(bookRepository.findByIdAndLibraryId(BOOK_ID, LIBRARY_ID)).thenReturn(Optional.of(book));
        when(userRepository.findByUsername(AUTHENTICATED_USERNAME)).thenReturn(Optional.of(borrower()));
        echoSavedTransaction();

        transactionService.issueBook(BOOK_ID, AUTHENTICATED_USERNAME, LocalDate.now().plusDays(14));

        assertThat(book.getAvailableCopies()).isEqualTo(2);
        assertThat(book.getTotalCopies()).isEqualTo(3);
        verify(bookRepository).save(book);
    }

    @Test
    void rejectsAnAuthenticatedNameThatMatchesNoAccount() {
        when(userRepository.findByUsername("ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> transactionService.issueBook(BOOK_ID, "ghost", LocalDate.now().plusDays(14)))
                .isInstanceOf(UserNotFoundException.class);

        // The caller is resolved before any book is read - it has to be, since
        // the caller's library is what scopes the book lookup. So a name that
        // matches no account never reaches the catalogue at all.
        verify(bookRepository, never()).findByIdAndLibraryId(anyLong(), anyLong());
        verify(transactionRepository, never()).save(any(Transaction.class));
    }

    @Test
    void stillRejectsAnUnknownBook() {
        when(userRepository.findByUsername(AUTHENTICATED_USERNAME)).thenReturn(Optional.of(borrower()));
        when(bookRepository.findByIdAndLibraryId(BOOK_ID, LIBRARY_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> transactionService.issueBook(BOOK_ID, AUTHENTICATED_USERNAME,
                LocalDate.now().plusDays(14)))
                .isInstanceOf(BookNotFoundException.class);

        verify(transactionRepository, never()).save(any(Transaction.class));
    }

    @Test
    void stillRejectsABookWithNoCopiesLeft() {
        when(bookRepository.findByIdAndLibraryId(BOOK_ID, LIBRARY_ID)).thenReturn(Optional.of(availableBook(0)));
        when(userRepository.findByUsername(AUTHENTICATED_USERNAME)).thenReturn(Optional.of(borrower()));

        assertThatThrownBy(() -> transactionService.issueBook(BOOK_ID, AUTHENTICATED_USERNAME,
                LocalDate.now().plusDays(14)))
                .isInstanceOf(BookNotAvailableException.class);

        verify(transactionRepository, never()).save(any(Transaction.class));
    }

    // ---------- due-date validation at the service layer ----------

    @Test
    void aDueDateBeforeTheIssueDateIsRejected() {
        assertThatThrownBy(() -> transactionService.issueBook(BOOK_ID, AUTHENTICATED_USERNAME,
                LocalDate.now().minusDays(1)))
                .isInstanceOf(InvalidDueDateException.class);
    }

    @Test
    void aDueDateFarInThePastIsRejected() {
        assertThatThrownBy(() -> transactionService.issueBook(BOOK_ID, AUTHENTICATED_USERNAME,
                LocalDate.of(2020, 1, 1)))
                .isInstanceOf(InvalidDueDateException.class);
    }

    @Test
    void aMissingDueDateIsRejectedRatherThanThrowingNullPointer() {
        // The DTO's @NotNull covers the HTTP boundary; a direct caller would
        // otherwise reach LocalDate.isBefore on null and turn a bad request
        // into a 500.
        assertThatThrownBy(() -> transactionService.issueBook(BOOK_ID, AUTHENTICATED_USERNAME, null))
                .isInstanceOf(InvalidDueDateException.class)
                .isNotInstanceOf(NullPointerException.class);
    }

    @Test
    void aRejectedDueDateTouchesNothing() {
        // Validated before any lookup, so a request that cannot produce a valid
        // loan never reads an account or a book, let alone writes one.
        assertThatThrownBy(() -> transactionService.issueBook(BOOK_ID, AUTHENTICATED_USERNAME,
                LocalDate.now().minusDays(1)))
                .isInstanceOf(InvalidDueDateException.class);

        verify(userRepository, never()).findByUsername(anyString());
        verify(bookRepository, never()).findByIdAndLibraryId(anyLong(), anyLong());
        verify(transactionRepository, never()).save(any(Transaction.class));
    }

    @Test
    void todayIsAcceptedAsADueDate() {
        // The boundary: same-day return is a real loan, so isBefore rather than
        // a "must be later" rule.
        when(userRepository.findByUsername(AUTHENTICATED_USERNAME)).thenReturn(Optional.of(borrower()));
        when(bookRepository.findByIdAndLibraryId(BOOK_ID, LIBRARY_ID))
                .thenReturn(Optional.of(availableBook(3)));
        echoSavedTransaction();

        LocalDate today = LocalDate.now();
        TransactionResponse response = transactionService.issueBook(BOOK_ID, AUTHENTICATED_USERNAME, today);

        assertThat(response.getDueDate()).isEqualTo(today);
        assertThat(response.getIssueDate()).isEqualTo(today);
    }

    @Test
    void anOrdinaryFutureDueDateIsStillAccepted() {
        when(userRepository.findByUsername(AUTHENTICATED_USERNAME)).thenReturn(Optional.of(borrower()));
        when(bookRepository.findByIdAndLibraryId(BOOK_ID, LIBRARY_ID))
                .thenReturn(Optional.of(availableBook(3)));
        echoSavedTransaction();

        LocalDate dueDate = LocalDate.now().plusDays(14);
        TransactionResponse response = transactionService.issueBook(BOOK_ID, AUTHENTICATED_USERNAME, dueDate);

        assertThat(response.getDueDate()).isEqualTo(dueDate);
        assertThat(response.getStatus()).isEqualTo(TransactionStatus.ISSUED);
    }

    @Test
    void theIssueDateIsStampedOnceAndIsNotBeforeTheDueDate() {
        // The invariant the guard establishes, asserted on the row that is
        // actually saved.
        when(userRepository.findByUsername(AUTHENTICATED_USERNAME)).thenReturn(Optional.of(borrower()));
        when(bookRepository.findByIdAndLibraryId(BOOK_ID, LIBRARY_ID))
                .thenReturn(Optional.of(availableBook(3)));
        echoSavedTransaction();

        transactionService.issueBook(BOOK_ID, AUTHENTICATED_USERNAME, LocalDate.now().plusDays(7));

        ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository).save(captor.capture());
        Transaction saved = captor.getValue();

        assertThat(saved.getIssueDate()).isEqualTo(LocalDate.now());
        assertThat(saved.getDueDate()).isAfterOrEqualTo(saved.getIssueDate());
    }

    @Test
    void theDtoStillCarriesItsOwnDueDateValidation() {
        // Belt and braces: the service guard is additional to @FutureOrPresent,
        // not a replacement for it.
        java.lang.annotation.Annotation[] annotations;
        try {
            annotations = IssueBookRequest.class.getDeclaredField("dueDate").getAnnotations();
        } catch (NoSuchFieldException e) {
            throw new AssertionError("dueDate field must exist on IssueBookRequest", e);
        }

        assertThat(Arrays.stream(annotations).map(a -> a.annotationType().getSimpleName()))
                .contains("NotNull", "FutureOrPresent");
    }

    @Test
    void anInvalidDueDateIsReportedAs400() {
        GlobalExceptionHandler.ErrorResponse body = new GlobalExceptionHandler()
                .handleInvalidDueDate(
                        new InvalidDueDateException(LocalDate.of(2020, 1, 1), LocalDate.of(2026, 1, 1)))
                .getBody();

        assertThat(body).isNotNull();
        assertThat(body.status()).isEqualTo(400);
        assertThat(body.message()).contains("2020-01-01").contains("2026-01-01");
    }

    @Test
    void anIssuedLoanNeverCarriesAReturnDate() {
        // The mirror of the RETURNED invariant in
        // TransactionServiceReturnBookTest, stated as one relationship rather
        // than two assertions that merely happen to sit in the same test: an
        // open loan is ISSUED and has no return date, and nothing about a
        // freshly created loan may say otherwise.
        when(userRepository.findByUsername(AUTHENTICATED_USERNAME)).thenReturn(Optional.of(borrower()));
        when(bookRepository.findByIdAndLibraryId(BOOK_ID, LIBRARY_ID))
                .thenReturn(Optional.of(availableBook(3)));
        echoSavedTransaction();

        transactionService.issueBook(BOOK_ID, AUTHENTICATED_USERNAME, LocalDate.now().plusDays(14));

        ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository).save(captor.capture());
        Transaction saved = captor.getValue();

        boolean issuedWithAReturnDate =
                saved.getStatus() == TransactionStatus.ISSUED && saved.getReturnDate() != null;
        assertThat(issuedWithAReturnDate)
                .as("ISSUED with a return date must never be written")
                .isFalse();
        assertThat(saved.getStatus()).isEqualTo(TransactionStatus.ISSUED);
        assertThat(saved.getReturnDate()).isNull();
    }
}
