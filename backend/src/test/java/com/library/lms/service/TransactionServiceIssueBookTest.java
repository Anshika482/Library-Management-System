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
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
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
import com.library.lms.exception.MemberNotEligibleException;
import com.library.lms.exception.UserNotFoundException;
import com.library.lms.repository.BookRepository;
import com.library.lms.repository.TransactionRepository;
import com.library.lms.repository.UserRepository;

/**
 * Guards who a book is issued to, and how that account is found.
 *
 * <p>The rule this class protected used to be the opposite one: the borrower
 * was whoever was logged in, and a request could not name anybody. That made a
 * library system that could not lend a book to a member - the endpoint is
 * staff-only, so the only loan it could record was a librarian borrowing from
 * themselves. A request now names the member, which moves the question from
 * "can a caller choose the borrower" to <b>"which borrowers may a caller
 * choose"</b>, and that is what the tests below pin.</p>
 *
 * <p>Two things carry the weight. The borrower is looked up <i>by id within the
 * caller's own library</i>, never by an unscoped id, so a member id from
 * another library is simply not found - and the assertion that the unscoped
 * {@code findById} is never called survives from the previous design, because
 * it is still exactly what must not happen. And an account that is found but
 * may not borrow - staff, disabled, or locked - is refused identically in all
 * three cases, so the endpoint cannot be used to read an account's status.</p>
 *
 * <p>Every collaborator is mocked, so no Spring context starts and no row is
 * read or written.</p>
 */
@ExtendWith(MockitoExtension.class)
class TransactionServiceIssueBookTest {

    /** The member of staff processing the loan. */
    private static final String STAFF_USERNAME = "a-librarian";

    /** The member taking the book home. */
    private static final Long MEMBER_ID = 42L;

    private static final String MEMBER_USERNAME = "a-member";

    private static final Long BOOK_ID = 7L;

    /** The caller's own library. Every fixture below belongs to it. */
    private static final Long LIBRARY_ID = 1L;

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

    private static User staffCaller() {
        User user = new User();
        user.setId(9L);
        user.setUsername(STAFF_USERNAME);
        user.setRole(Role.ROLE_LIBRARIAN);
        user.setLibrary(library(LIBRARY_ID));
        return user;
    }

    /** An ordinary member: enabled and unlocked, as a new account is. */
    private static User member() {
        return member(Role.ROLE_MEMBER, true, true);
    }

    private static User member(Role role, boolean enabled, boolean accountNonLocked) {
        User user = new User();
        user.setId(MEMBER_ID);
        user.setUsername(MEMBER_USERNAME);
        user.setRole(role);
        user.setLibrary(library(LIBRARY_ID));
        user.setEnabled(enabled);
        user.setAccountNonLocked(accountNonLocked);
        return user;
    }

    // ---------- stubs, kept separate so each test declares only what it reaches ----------

    private void callerIsKnown() {
        when(userRepository.findByUsername(STAFF_USERNAME)).thenReturn(Optional.of(staffCaller()));
    }

    private void bookIsAvailable(int copies) {
        when(bookRepository.findByIdAndLibraryId(BOOK_ID, LIBRARY_ID)).thenReturn(Optional.of(availableBook(copies)));
    }

    private void borrowerIs(User borrower) {
        when(userRepository.findByIdAndLibraryId(MEMBER_ID, LIBRARY_ID)).thenReturn(Optional.of(borrower));
    }

    /** Hands back whatever the service asked to save, with an id filled in. */
    private void echoSavedTransaction() {
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(invocation -> {
            Transaction saved = invocation.getArgument(0);
            saved.setId(1001L);
            return saved;
        });
    }

    private Transaction savedTransaction() {
        ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository).save(captor.capture());
        return captor.getValue();
    }

    private TransactionResponse issue(LocalDate dueDate) {
        return transactionService.issueBook(BOOK_ID, MEMBER_ID, STAFF_USERNAME, dueDate);
    }

    // ---------- what a request may say ----------

    @Test
    void theRequestNamesTheBorrowerByIdAndNothingElseAboutThem() {
        assertThat(Arrays.stream(IssueBookRequest.class.getDeclaredFields()).map(Field::getName))
                .as("a book, a borrower and a due date - nothing more")
                .containsExactlyInAnyOrder("bookId", "memberId", "dueDate");

        assertThat(Arrays.stream(IssueBookRequest.class.getDeclaredFields()).map(Field::getName))
                .as("naming a library would let a caller aim the loan at another tenant")
                .doesNotContain("libraryId", "username", "library");
    }

    // ---------- where each account comes from ----------

    @Test
    void theCallerComesFromTheLoginNameAndTheBorrowerFromTheRequest() {
        callerIsKnown();
        bookIsAvailable(3);
        borrowerIs(member());
        echoSavedTransaction();

        issue(LocalDate.now().plusDays(14));

        verify(userRepository).findByUsername(STAFF_USERNAME);
        verify(userRepository).findByIdAndLibraryId(MEMBER_ID, LIBRARY_ID);
    }

    @Test
    void theBorrowerIsNeverLookedUpWithAnUnscopedQuery() {
        // The borrower id arrives in the request body, so an unscoped lookup
        // would let a member of another library be named and lent to.
        callerIsKnown();
        bookIsAvailable(3);
        borrowerIs(member());
        echoSavedTransaction();

        issue(LocalDate.now().plusDays(14));

        verify(userRepository, never()).findById(anyLong());
    }

    @Test
    void theLoanIsSavedAgainstTheMemberNotTheCaller() {
        User borrower = member();
        callerIsKnown();
        bookIsAvailable(3);
        borrowerIs(borrower);
        echoSavedTransaction();

        LocalDate dueDate = LocalDate.now().plusDays(14);
        issue(dueDate);

        Transaction saved = savedTransaction();
        assertThat(saved.getUser()).isSameAs(borrower);
        assertThat(saved.getUser().getUsername()).isEqualTo(MEMBER_USERNAME);
        assertThat(saved.getUser().getUsername())
                .as("the librarian handed the book over; they did not borrow it")
                .isNotEqualTo(STAFF_USERNAME);
        assertThat(saved.getBook().getId()).isEqualTo(BOOK_ID);
        assertThat(saved.getLibrary().getId())
                .as("book, borrower and loan all belong to the caller's library")
                .isEqualTo(LIBRARY_ID);
        assertThat(saved.getDueDate()).isEqualTo(dueDate);
        assertThat(saved.getIssueDate()).isEqualTo(LocalDate.now());
        assertThat(saved.getStatus()).isEqualTo(TransactionStatus.ISSUED);
        assertThat(saved.getReturnDate()).isNull();
        assertThat(saved.getFineAmount()).isNull();
    }

    @Test
    void decrementsAvailableCopiesAndLeavesTotalAlone() {
        Book book = availableBook(3);
        callerIsKnown();
        when(bookRepository.findByIdAndLibraryId(BOOK_ID, LIBRARY_ID)).thenReturn(Optional.of(book));
        borrowerIs(member());
        echoSavedTransaction();

        issue(LocalDate.now().plusDays(14));

        assertThat(book.getAvailableCopies()).isEqualTo(2);
        assertThat(book.getTotalCopies()).isEqualTo(3);
        verify(bookRepository).save(book);
    }

    // ---------- borrowers that are refused ----------

    @Test
    void aMemberIdFromAnotherLibraryIsSimplyNotFound() {
        callerIsKnown();
        bookIsAvailable(3);
        when(userRepository.findByIdAndLibraryId(MEMBER_ID, LIBRARY_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> issue(LocalDate.now().plusDays(14)))
                .as("the same answer an id belonging to nobody gets")
                .isInstanceOf(UserNotFoundException.class);

        verify(transactionRepository, never()).save(any(Transaction.class));
        verify(bookRepository, never()).save(any(Book.class));
    }

    @Test
    void aStaffAccountMayNotBorrow() {
        callerIsKnown();
        bookIsAvailable(3);
        borrowerIs(member(Role.ROLE_LIBRARIAN, true, true));

        assertThatThrownBy(() -> issue(LocalDate.now().plusDays(14)))
                .isInstanceOf(MemberNotEligibleException.class);

        verify(transactionRepository, never()).save(any(Transaction.class));
        verify(bookRepository, never()).save(any(Book.class));
    }

    @Test
    void aDisabledMemberMayNotBorrow() {
        callerIsKnown();
        bookIsAvailable(3);
        borrowerIs(member(Role.ROLE_MEMBER, false, true));

        assertThatThrownBy(() -> issue(LocalDate.now().plusDays(14)))
                .isInstanceOf(MemberNotEligibleException.class);

        verify(bookRepository, never()).save(any(Book.class));
    }

    @Test
    void aLockedMemberMayNotBorrow() {
        callerIsKnown();
        bookIsAvailable(3);
        borrowerIs(member(Role.ROLE_MEMBER, true, false));

        assertThatThrownBy(() -> issue(LocalDate.now().plusDays(14)))
                .isInstanceOf(MemberNotEligibleException.class);

        verify(bookRepository, never()).save(any(Book.class));
    }

    @Test
    void everyIneligibleBorrowerIsRefusedWithTheSameMessage() {
        // Staff, disabled and locked are three different reasons; telling them
        // apart would make this endpoint a way to read an account's status.
        assertThat(new MemberNotEligibleException().getMessage())
                .isEqualTo("Books can only be issued to an active member account.");
    }

    // ---------- the caller and the book, as before ----------

    @Test
    void rejectsAnAuthenticatedNameThatMatchesNoAccount() {
        when(userRepository.findByUsername("ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> transactionService.issueBook(BOOK_ID, MEMBER_ID, "ghost",
                LocalDate.now().plusDays(14)))
                .isInstanceOf(UserNotFoundException.class);

        // The caller is resolved before any book is read - it has to be, since
        // the caller's library is what scopes the book lookup. So a name that
        // matches no account never reaches the catalogue at all.
        verify(bookRepository, never()).findByIdAndLibraryId(anyLong(), anyLong());
        verify(transactionRepository, never()).save(any(Transaction.class));
    }

    @Test
    void stillRejectsAnUnknownBook() {
        callerIsKnown();
        when(bookRepository.findByIdAndLibraryId(BOOK_ID, LIBRARY_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> issue(LocalDate.now().plusDays(14)))
                .isInstanceOf(BookNotFoundException.class);

        verify(transactionRepository, never()).save(any(Transaction.class));
    }

    @Test
    void stillRejectsABookWithNoCopiesLeft() {
        callerIsKnown();
        bookIsAvailable(0);
        borrowerIs(member());

        assertThatThrownBy(() -> issue(LocalDate.now().plusDays(14)))
                .isInstanceOf(BookNotAvailableException.class);

        verify(transactionRepository, never()).save(any(Transaction.class));
    }

    // ---------- due-date validation at the service layer ----------

    @Test
    void aDueDateBeforeTheIssueDateIsRejected() {
        assertThatThrownBy(() -> issue(LocalDate.now().minusDays(1)))
                .isInstanceOf(InvalidDueDateException.class);
    }

    @Test
    void aDueDateFarInThePastIsRejected() {
        assertThatThrownBy(() -> issue(LocalDate.of(2020, 1, 1)))
                .isInstanceOf(InvalidDueDateException.class);
    }

    @Test
    void aMissingDueDateIsRejectedRatherThanThrowingNullPointer() {
        // The DTO's @NotNull covers the HTTP boundary; a direct caller would
        // otherwise reach LocalDate.isBefore on null and turn a bad request
        // into a 500.
        assertThatThrownBy(() -> issue(null))
                .isInstanceOf(InvalidDueDateException.class)
                .isNotInstanceOf(NullPointerException.class);
    }

    @Test
    void aRejectedDueDateTouchesNothing() {
        // Validated before any lookup, so a request that cannot produce a valid
        // loan never reads an account or a book, let alone writes one.
        assertThatThrownBy(() -> issue(LocalDate.now().minusDays(1)))
                .isInstanceOf(InvalidDueDateException.class);

        verify(userRepository, never()).findByUsername(anyString());
        verify(userRepository, never()).findByIdAndLibraryId(anyLong(), anyLong());
        verify(bookRepository, never()).findByIdAndLibraryId(anyLong(), anyLong());
        verify(transactionRepository, never()).save(any(Transaction.class));
    }

    @Test
    void todayIsAcceptedAsADueDate() {
        // The boundary: same-day return is a real loan, so isBefore rather than
        // a "must be later" rule.
        callerIsKnown();
        bookIsAvailable(3);
        borrowerIs(member());
        echoSavedTransaction();

        LocalDate today = LocalDate.now();
        TransactionResponse response = issue(today);

        assertThat(response.getDueDate()).isEqualTo(today);
        assertThat(response.getIssueDate()).isEqualTo(today);
    }

    @Test
    void anOrdinaryFutureDueDateIsStillAccepted() {
        callerIsKnown();
        bookIsAvailable(3);
        borrowerIs(member());
        echoSavedTransaction();

        LocalDate dueDate = LocalDate.now().plusDays(14);
        TransactionResponse response = issue(dueDate);

        assertThat(response.getDueDate()).isEqualTo(dueDate);
        assertThat(response.getUserId()).as("the response names the borrower").isEqualTo(MEMBER_ID);
        assertThat(response.getStatus()).isEqualTo(TransactionStatus.ISSUED);
    }

    @Test
    void theIssueDateIsStampedOnceAndIsNotBeforeTheDueDate() {
        // The invariant the guard establishes, asserted on the row that is
        // actually saved.
        callerIsKnown();
        bookIsAvailable(3);
        borrowerIs(member());
        echoSavedTransaction();

        issue(LocalDate.now().plusDays(7));

        Transaction saved = savedTransaction();
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
    void theDtoValidatesTheMemberIdItNowCarries() {
        java.lang.annotation.Annotation[] annotations;
        try {
            annotations = IssueBookRequest.class.getDeclaredField("memberId").getAnnotations();
        } catch (NoSuchFieldException e) {
            throw new AssertionError("memberId field must exist on IssueBookRequest", e);
        }

        assertThat(Arrays.stream(annotations).map(a -> a.annotationType().getSimpleName()))
                .as("a missing or nonsensical id must be refused before any lookup")
                .contains("NotNull", "Positive");
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
    void anIneligibleBorrowerIsReportedAs400() {
        GlobalExceptionHandler.ErrorResponse body = new GlobalExceptionHandler()
                .handleMemberNotEligible(new MemberNotEligibleException())
                .getBody();

        assertThat(body).isNotNull();
        assertThat(body.status()).isEqualTo(400);
        assertThat(body.message()).isEqualTo("Books can only be issued to an active member account.");
    }

    @Test
    void anIssuedLoanNeverCarriesAReturnDate() {
        // The mirror of the RETURNED invariant in
        // TransactionServiceReturnBookTest, stated as one relationship rather
        // than two assertions that merely happen to sit in the same test: an
        // open loan is ISSUED and has no return date, and nothing about a
        // freshly created loan may say otherwise.
        callerIsKnown();
        bookIsAvailable(3);
        borrowerIs(member());
        echoSavedTransaction();

        issue(LocalDate.now().plusDays(14));

        Transaction saved = savedTransaction();
        boolean issuedWithAReturnDate =
                saved.getStatus() == TransactionStatus.ISSUED && saved.getReturnDate() != null;
        assertThat(issuedWithAReturnDate)
                .as("ISSUED with a return date must never be written")
                .isFalse();
        assertThat(saved.getStatus()).isEqualTo(TransactionStatus.ISSUED);
        assertThat(saved.getReturnDate()).isNull();
    }
}
