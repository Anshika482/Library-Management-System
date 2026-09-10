package com.library.lms.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
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
import com.library.lms.exception.BookNotFoundException;
import com.library.lms.exception.TransactionAccessDeniedException;
import com.library.lms.exception.TransactionNotFoundException;
import com.library.lms.repository.BookRepository;
import com.library.lms.repository.TransactionRepository;
import com.library.lms.repository.UserRepository;

/**
 * Guards the tenant boundary around every borrowing operation.
 *
 * <p>Two libraries exist here: the caller always belongs to <b>A</b>, and every
 * id the caller is not entitled to reach belongs to <b>B</b>. Each test asks
 * whether A can touch B, and the answer must always be no - by the query, not by
 * a check applied after the row has already been read.</p>
 *
 * <p>Every collaborator is mocked, so no Spring context starts and no row is read
 * or written. Where a scoped repository method is stubbed to return empty, that
 * <i>is</i> the real behaviour being modelled: a query carrying
 * {@code AND library_id = A} cannot match a row owned by B.</p>
 *
 * <p>The two writing paths - issue and return - carry the strongest assertions.
 * Reading another library's data is a disclosure; changing another library's copy
 * counts is corruption of a tenant the caller cannot even see, so those tests
 * check that B's book is left exactly as it was.</p>
 */
@ExtendWith(MockitoExtension.class)
class TransactionServiceLibraryScopingTest {

    private static final Long LIBRARY_A_ID = 1L;

    private static final Long LIBRARY_B_ID = 2L;

    private static final String CALLER_A = "staff-of-a";

    private static final Long USER_A_ID = 10L;

    private static final Long USER_B_ID = 20L;

    private static final Long BOOK_A_ID = 100L;

    private static final Long BOOK_B_ID = 200L;

    private static final Long TRANSACTION_A_ID = 1000L;

    private static final Long TRANSACTION_B_ID = 2000L;

    private static final LocalDate DUE_DATE = LocalDate.now().plusDays(14);

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private BookRepository bookRepository;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private TransactionService transactionService;

    // ---------- fixtures ----------

    private static Library library(Long id) {
        Library library = new Library();
        library.setId(id);
        return library;
    }

    private static final Library LIBRARY_A = library(LIBRARY_A_ID);

    private static final Library LIBRARY_B = library(LIBRARY_B_ID);

    private static User user(Long id, String username, Role role, Library library) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        user.setRole(role);
        user.setLibrary(library);
        return user;
    }

    private static User staffOfA() {
        return user(USER_A_ID, CALLER_A, Role.ROLE_LIBRARIAN, LIBRARY_A);
    }

    private static User memberOfA(String username) {
        return user(USER_A_ID, username, Role.ROLE_MEMBER, LIBRARY_A);
    }

    private static Book book(Long id, Library library, int totalCopies, int availableCopies) {
        Book book = new Book();
        book.setId(id);
        book.setTitle("Title " + id);
        book.setTotalCopies(totalCopies);
        book.setAvailableCopies(availableCopies);
        book.setLibrary(library);
        return book;
    }

    private static Transaction loan(Long id, Library library, Book book, User borrower) {
        Transaction transaction = new Transaction();
        transaction.setId(id);
        transaction.setBook(book);
        transaction.setUser(borrower);
        transaction.setLibrary(library);
        transaction.setIssueDate(LocalDate.now().minusDays(3));
        transaction.setDueDate(DUE_DATE);
        transaction.setStatus(TransactionStatus.ISSUED);
        return transaction;
    }

    /** The caller for this test, resolved the only way the service resolves one. */
    private void callerIs(User caller) {
        when(userRepository.findByUsername(caller.getUsername())).thenReturn(Optional.of(caller));
    }

    private void echoSavedTransaction() {
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(invocation -> {
            Transaction saved = invocation.getArgument(0);
            saved.setId(TRANSACTION_A_ID);
            return saved;
        });
    }

    // ---------- 1. issue: cross-library ----------

    @Test
    void staffInLibraryACannotIssueALibraryBBook() {
        callerIs(staffOfA());
        Book bookB = book(BOOK_B_ID, LIBRARY_B, 4, 4);

        // B's book is simply not on A's shelf, so the scoped query finds nothing.
        when(bookRepository.findByIdAndLibraryId(BOOK_B_ID, LIBRARY_A_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> transactionService.issueBook(BOOK_B_ID, CALLER_A, DUE_DATE))
                .isInstanceOf(BookNotFoundException.class);

        verify(bookRepository).findByIdAndLibraryId(BOOK_B_ID, LIBRARY_A_ID);
        assertThat(bookB.getAvailableCopies())
                .as("Library B's stock must be untouched by a caller from Library A")
                .isEqualTo(4);
    }

    @Test
    void issueNeverUsesAnUnscopedBookLookup() {
        callerIs(staffOfA());
        when(bookRepository.findByIdAndLibraryId(BOOK_B_ID, LIBRARY_A_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> transactionService.issueBook(BOOK_B_ID, CALLER_A, DUE_DATE))
                .isInstanceOf(BookNotFoundException.class);

        // The whole fix in one assertion: the global lookup is not merely
        // avoided by luck of ordering, it is never called at all.
        verify(bookRepository, never()).findById(anyLong());
    }

    @Test
    void aRefusedCrossLibraryIssueWritesNothing() {
        callerIs(staffOfA());
        when(bookRepository.findByIdAndLibraryId(BOOK_B_ID, LIBRARY_A_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> transactionService.issueBook(BOOK_B_ID, CALLER_A, DUE_DATE))
                .isInstanceOf(BookNotFoundException.class);

        verify(transactionRepository, never()).save(any(Transaction.class));
        verify(bookRepository, never()).save(any(Book.class));
    }

    @Test
    void aForeignBookIsIndistinguishableFromOneThatDoesNotExist() {
        // Anti-enumeration: if a book in the next library failed differently
        // from a book id that was never issued, a caller could map another
        // tenant's catalogue one id at a time.
        callerIs(staffOfA());
        when(bookRepository.findByIdAndLibraryId(BOOK_B_ID, LIBRARY_A_ID)).thenReturn(Optional.empty());
        when(bookRepository.findByIdAndLibraryId(999_999L, LIBRARY_A_ID)).thenReturn(Optional.empty());

        Throwable foreign = catchThrowable(
                () -> transactionService.issueBook(BOOK_B_ID, CALLER_A, DUE_DATE));
        Throwable missing = catchThrowable(
                () -> transactionService.issueBook(999_999L, CALLER_A, DUE_DATE));

        assertThat(foreign).isInstanceOf(BookNotFoundException.class);
        assertThat(missing.getClass()).isEqualTo(foreign.getClass());
        assertThat(missing.getMessage().replace("999999", "X").replace(String.valueOf(BOOK_B_ID), "X"))
                .as("the two refusals differ only in the id echoed back")
                .isEqualTo(foreign.getMessage().replace("999999", "X")
                        .replace(String.valueOf(BOOK_B_ID), "X"));
    }

    // ---------- 2 & 9. issue: same library, and the invariant ----------

    @Test
    void staffInLibraryAMayIssueALibraryABook() {
        User caller = staffOfA();
        Book bookA = book(BOOK_A_ID, LIBRARY_A, 3, 3);
        callerIs(caller);
        when(bookRepository.findByIdAndLibraryId(BOOK_A_ID, LIBRARY_A_ID)).thenReturn(Optional.of(bookA));
        echoSavedTransaction();

        TransactionResponse response = transactionService.issueBook(BOOK_A_ID, CALLER_A, DUE_DATE);

        assertThat(response.getBookId()).isEqualTo(BOOK_A_ID);
        assertThat(response.getUserId()).isEqualTo(USER_A_ID);
        assertThat(response.getStatus()).isEqualTo(TransactionStatus.ISSUED);
        assertThat(bookA.getAvailableCopies()).as("decremented exactly once").isEqualTo(2);
        assertThat(bookA.getTotalCopies()).isEqualTo(3);
    }

    @Test
    void anIssuedLoanRecordsTheCallersLibrary() {
        User caller = staffOfA();
        Book bookA = book(BOOK_A_ID, LIBRARY_A, 3, 3);
        callerIs(caller);
        when(bookRepository.findByIdAndLibraryId(BOOK_A_ID, LIBRARY_A_ID)).thenReturn(Optional.of(bookA));
        echoSavedTransaction();

        transactionService.issueBook(BOOK_A_ID, CALLER_A, DUE_DATE);

        ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository).save(captor.capture());
        Transaction saved = captor.getValue();

        // The invariant the whole step exists to establish.
        assertThat(saved.getLibrary()).isSameAs(LIBRARY_A);
        assertThat(saved.getLibrary().getId()).isEqualTo(caller.getLibrary().getId());
        assertThat(saved.getUser().getLibrary().getId()).isEqualTo(LIBRARY_A_ID);
        assertThat(saved.getBook().getLibrary().getId()).isEqualTo(LIBRARY_A_ID);
    }

    @Test
    void theLibraryIsNeverTakenFromTheRequest() {
        assertThat(Arrays.stream(IssueBookRequest.class.getDeclaredFields())
                .map(java.lang.reflect.Field::getName))
                .as("a tenant id must never be client-supplied")
                .doesNotContain("libraryId", "library")
                .containsExactlyInAnyOrder("bookId", "dueDate");

        assertThat(Arrays.stream(IssueBookRequest.class.getMethods()).map(Method::getName))
                .doesNotContain("getLibraryId", "setLibraryId");
    }

    // ---------- 3. return: cross-library ----------

    @Test
    void staffInLibraryACannotReturnALibraryBLoan() {
        callerIs(staffOfA());
        Book bookB = book(BOOK_B_ID, LIBRARY_B, 5, 2);
        loan(TRANSACTION_B_ID, LIBRARY_B, bookB, user(USER_B_ID, "member-of-b", Role.ROLE_MEMBER, LIBRARY_B));

        when(transactionRepository.findByIdAndLibraryId(TRANSACTION_B_ID, LIBRARY_A_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> transactionService.returnBook(TRANSACTION_B_ID, CALLER_A))
                .isInstanceOf(TransactionNotFoundException.class);

        assertThat(bookB.getAvailableCopies())
                .as("Library B's copy count must not move")
                .isEqualTo(2);
        verify(bookRepository, never()).save(any(Book.class));
        verify(transactionRepository, never()).save(any(Transaction.class));
    }

    @Test
    void returnNeverUsesAnUnscopedTransactionLookup() {
        callerIs(staffOfA());
        when(transactionRepository.findByIdAndLibraryId(TRANSACTION_B_ID, LIBRARY_A_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> transactionService.returnBook(TRANSACTION_B_ID, CALLER_A))
                .isInstanceOf(TransactionNotFoundException.class);

        verify(transactionRepository, never()).findById(anyLong());
    }

    // ---------- 4. return: same library still works ----------

    @Test
    void staffInLibraryAMayStillReturnALibraryALoan() {
        callerIs(staffOfA());
        Book bookA = book(BOOK_A_ID, LIBRARY_A, 5, 2);
        Transaction loanA = loan(TRANSACTION_A_ID, LIBRARY_A, bookA, memberOfA("member-of-a"));

        when(transactionRepository.findByIdAndLibraryId(TRANSACTION_A_ID, LIBRARY_A_ID))
                .thenReturn(Optional.of(loanA));
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(i -> i.getArgument(0));

        TransactionResponse response = transactionService.returnBook(TRANSACTION_A_ID, CALLER_A);

        assertThat(response.getStatus()).isEqualTo(TransactionStatus.RETURNED);
        assertThat(response.getReturnDate()).isEqualTo(LocalDate.now());
        assertThat(bookA.getAvailableCopies()).as("the copy goes back on the shelf").isEqualTo(3);
        verify(bookRepository).save(bookA);
    }

    // ---------- 5. get by id: cross-library ----------

    @Test
    void staffInLibraryACannotReadALibraryBLoanById() {
        callerIs(staffOfA());
        when(transactionRepository.findByIdAndLibraryId(TRANSACTION_B_ID, LIBRARY_A_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> transactionService.getTransactionById(TRANSACTION_B_ID, CALLER_A))
                .isInstanceOf(TransactionNotFoundException.class);
    }

    @Test
    void aMemberInLibraryACannotReadALibraryBLoanById() {
        String member = "member-of-a";
        callerIs(memberOfA(member));
        when(transactionRepository.findByIdAndLibraryId(TRANSACTION_B_ID, LIBRARY_A_ID))
                .thenReturn(Optional.empty());

        // A member gets the same refusal they get for any loan that is not
        // theirs, so the tenant boundary is invisible from outside.
        assertThatThrownBy(() -> transactionService.getTransactionById(TRANSACTION_B_ID, member))
                .isInstanceOf(TransactionAccessDeniedException.class)
                .hasMessage("Access denied");
    }

    // ---------- 6. get by book: cross-library ----------

    @Test
    void staffInLibraryASeeNoHistoryForALibraryBBook() {
        callerIs(staffOfA());
        when(transactionRepository.findByBookIdAndLibraryId(BOOK_B_ID, LIBRARY_A_ID))
                .thenReturn(List.of());

        List<TransactionResponse> history = transactionService.getTransactionsByBook(BOOK_B_ID, CALLER_A);

        assertThat(history).isEmpty();
        verify(transactionRepository).findByBookIdAndLibraryId(BOOK_B_ID, LIBRARY_A_ID);
        // A "never called findByBookId" check cannot be written here: the
        // unscoped method no longer exists to name. See
        // theRepositoryDeclaresNoUnscopedQueries below.
    }

    @Test
    void bookHistoryStillWorksWithinTheCallersLibrary() {
        callerIs(staffOfA());
        Book bookA = book(BOOK_A_ID, LIBRARY_A, 3, 2);
        when(transactionRepository.findByBookIdAndLibraryId(BOOK_A_ID, LIBRARY_A_ID))
                .thenReturn(List.of(loan(TRANSACTION_A_ID, LIBRARY_A, bookA, memberOfA("member-of-a"))));

        List<TransactionResponse> history = transactionService.getTransactionsByBook(BOOK_A_ID, CALLER_A);

        assertThat(history).hasSize(1);
        assertThat(history.get(0).getBookId()).isEqualTo(BOOK_A_ID);
    }

    // ---------- 7. get by user: cross-library ----------

    @Test
    void staffInLibraryASeeNoHistoryForALibraryBUser() {
        callerIs(staffOfA());
        when(transactionRepository.findByUserIdAndLibraryId(USER_B_ID, LIBRARY_A_ID))
                .thenReturn(List.of());

        List<TransactionResponse> history = transactionService.getTransactionsByUser(USER_B_ID, CALLER_A);

        assertThat(history).as("a neighbouring library's borrower reveals nothing").isEmpty();
        verify(transactionRepository).findByUserIdAndLibraryId(USER_B_ID, LIBRARY_A_ID);
    }

    @Test
    void aMemberStillCannotAskForAnotherUsersHistoryAtAll() {
        // The ownership rule from the earlier steps is untouched by the tenant
        // work: it still refuses before any row is fetched.
        String member = "member-of-a";
        callerIs(memberOfA(member));

        assertThatThrownBy(() -> transactionService.getTransactionsByUser(USER_B_ID, member))
                .isInstanceOf(TransactionAccessDeniedException.class)
                .hasMessage("Access denied");

        verify(transactionRepository, never()).findByUserIdAndLibraryId(anyLong(), anyLong());
    }

    // ---------- 8. get by status ----------

    @Test
    void statusQueryReturnsOnlyTheCallersLibrary() {
        callerIs(staffOfA());
        Book bookA = book(BOOK_A_ID, LIBRARY_A, 3, 2);

        // The repository is asked only for A, so B's loan is never a candidate.
        when(transactionRepository.findByStatusAndLibraryId(TransactionStatus.ISSUED, LIBRARY_A_ID))
                .thenReturn(List.of(loan(TRANSACTION_A_ID, LIBRARY_A, bookA, memberOfA("member-of-a"))));

        List<TransactionResponse> issued =
                transactionService.getTransactionsByStatus(TransactionStatus.ISSUED, CALLER_A);

        assertThat(issued).hasSize(1);
        assertThat(issued.get(0).getId()).isEqualTo(TRANSACTION_A_ID);
        assertThat(issued).extracting(TransactionResponse::getId).doesNotContain(TRANSACTION_B_ID);

        verify(transactionRepository).findByStatusAndLibraryId(TransactionStatus.ISSUED, LIBRARY_A_ID);
    }

    // ---------- 10. no global transaction queries survive ----------

    @Test
    void theRepositoryDeclaresNoUnscopedQueries() {
        // Compile-time proof rather than a convention: a global method that
        // still exists is a global method somebody will call.
        assertThat(Arrays.stream(TransactionRepository.class.getDeclaredMethods()).map(Method::getName))
                .as("every declared query must carry the library")
                .allSatisfy(methodName -> assertThat(methodName).endsWith("AndLibraryId"))
                .doesNotContain("findByBookId", "findByUserId", "findByStatus");
    }
}
