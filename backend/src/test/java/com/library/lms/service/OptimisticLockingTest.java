package com.library.lms.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.time.LocalDate;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.annotation.Transactional;

import com.library.lms.dto.BookRequest;
import com.library.lms.dto.BookResponse;
import com.library.lms.dto.TransactionResponse;
import com.library.lms.entity.Book;
import com.library.lms.entity.Library;
import com.library.lms.entity.Role;
import com.library.lms.entity.Transaction;
import com.library.lms.entity.TransactionStatus;
import com.library.lms.entity.User;
import com.library.lms.exception.GlobalExceptionHandler;
import com.library.lms.repository.BookRepository;
import com.library.lms.repository.CategoryRepository;
import com.library.lms.repository.TransactionRepository;
import com.library.lms.repository.UserRepository;

import jakarta.persistence.Version;

/**
 * Guards the optimistic locking that stops two requests silently overwriting
 * each other on the same row.
 *
 * <p>The defect this protects against is not an exception anybody sees - it is
 * the absence of one. Two callers read {@code availableCopies = 1}, both decide
 * to write {@code 0}, and the second write lands on top of the first with no
 * complaint at all: one copy, two loans, and a ledger that disagrees with the
 * loan records for good. A version column turns that silence into a refusal.</p>
 *
 * <p>Every collaborator is mocked, so no Spring context starts and no row is
 * read or written. A real version clash cannot arise against a mock, so it is
 * modelled the way Spring Data actually reports one: the {@code save} throws
 * {@link ObjectOptimisticLockingFailureException}. What these tests then check
 * is the part that is genuinely ours - that the service lets it out rather than
 * swallowing it, that nothing is half-written when it happens, and that the
 * handler turns it into a 409 the caller can act on.</p>
 */
@ExtendWith(MockitoExtension.class)
class OptimisticLockingTest {

    private static final Long LIBRARY_ID = 1L;

    private static final String CALLER = "a-librarian";

    private static final Long BOOK_ID = 100L;

    private static final Long MEMBER_ID = 55L;

    private static final Long TRANSACTION_ID = 900L;

    private static final LocalDate DUE_DATE = LocalDate.now().plusDays(14);

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private BookRepository bookRepository;

    @Mock
    private CategoryRepository categoryRepository;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private TransactionService transactionService;

    private BookService bookService;

    private BookService bookService() {
        if (bookService == null) {
            bookService = new BookService(bookRepository, categoryRepository, userRepository,
                    transactionRepository);
        }
        return bookService;
    }

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
        book.setIsbn("978-0000000000");
        book.setTotalCopies(totalCopies);
        book.setAvailableCopies(availableCopies);
        book.setLibrary(library());
        return book;
    }

    private static Transaction openLoan(Book book) {
        Transaction transaction = new Transaction();
        transaction.setId(TRANSACTION_ID);
        transaction.setBook(book);
        transaction.setUser(caller());
        transaction.setLibrary(library());
        transaction.setIssueDate(LocalDate.now().minusDays(3));
        transaction.setDueDate(DUE_DATE);
        transaction.setStatus(TransactionStatus.ISSUED);
        return transaction;
    }

    private static BookRequest request(int totalCopies) {
        BookRequest request = new BookRequest();
        request.setTitle("A Book");
        request.setAuthor("An Author");
        request.setIsbn("978-0000000000");
        request.setTotalCopies(totalCopies);
        return request;
    }

    private void callerIsKnown() {
        when(userRepository.findByUsername(CALLER)).thenReturn(Optional.of(caller()));
    }

    /** The member a book is issued to, looked up inside the caller's library. */
    private void memberIsKnown() {
        User member = new User();
        member.setId(MEMBER_ID);
        member.setUsername("a-member");
        member.setRole(Role.ROLE_MEMBER);
        member.setLibrary(library());

        when(userRepository.findByIdAndLibraryId(MEMBER_ID, LIBRARY_ID)).thenReturn(Optional.of(member));
    }

    /** A clash reported exactly as Spring Data reports a failed version check. */
    private static ObjectOptimisticLockingFailureException clash(Class<?> type, Object id) {
        return new ObjectOptimisticLockingFailureException(type, id);
    }

    // ---------- the mapping itself ----------

    @Test
    void bookCarriesAVersionForOptimisticLocking() throws Exception {
        Field version = Book.class.getDeclaredField("version");

        assertThat(version.getAnnotation(Version.class))
                .as("without @Version every update is a blind overwrite")
                .isNotNull();
        assertThat(version.getType()).isEqualTo(Long.class);
    }

    @Test
    void transactionCarriesAVersionForOptimisticLocking() throws Exception {
        Field version = Transaction.class.getDeclaredField("version");

        assertThat(version.getAnnotation(Version.class)).isNotNull();
        assertThat(version.getType()).isEqualTo(Long.class);
    }

    // ---------- stale Book writes ----------

    @Test
    void aStaleBookUpdateIsRefusedRatherThanOverwriting() {
        callerIsKnown();
        when(bookRepository.findByIdAndLibraryId(BOOK_ID, LIBRARY_ID))
                .thenReturn(Optional.of(book(10, 7)));
        when(bookRepository.findByIsbnAndLibraryId(anyString(), eq(LIBRARY_ID)))
                .thenReturn(Optional.empty());
        when(bookRepository.save(any(Book.class))).thenThrow(clash(Book.class, BOOK_ID));

        assertThatThrownBy(() -> bookService().updateBook(BOOK_ID, request(12), CALLER))
                .as("the losing writer must be told, not silently ignored")
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
    }

    @Test
    void aStaleIssueIsRefusedAndWritesNoLoan() {
        callerIsKnown();
        when(bookRepository.findByIdAndLibraryId(BOOK_ID, LIBRARY_ID))
                .thenReturn(Optional.of(book(3, 1)));
        memberIsKnown();
        when(bookRepository.save(any(Book.class))).thenThrow(clash(Book.class, BOOK_ID));

        assertThatThrownBy(() -> transactionService.issueBook(BOOK_ID, MEMBER_ID, CALLER, DUE_DATE))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);

        // The exact defect from the audit: two callers both saw the last copy.
        // The loser must not end up with a loan against a copy it never got.
        verify(transactionRepository, never()).save(any(Transaction.class));
    }

    @Test
    void aStaleReturnIsRefusedAndWritesNoLoanUpdate() {
        callerIsKnown();
        Book book = book(5, 2);
        when(transactionRepository.findByIdAndLibraryId(TRANSACTION_ID, LIBRARY_ID))
                .thenReturn(Optional.of(openLoan(book)));
        when(bookRepository.save(any(Book.class))).thenThrow(clash(Book.class, BOOK_ID));

        assertThatThrownBy(() -> transactionService.returnBook(TRANSACTION_ID, CALLER))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);

        verify(transactionRepository, never()).save(any(Transaction.class));
    }

    // ---------- stale Transaction writes ----------

    @Test
    void aStaleTransactionWriteIsRefused() {
        // Two returns of the same loan: both read it as ISSUED and both pass the
        // status check, so the version on Transaction is what separates them.
        callerIsKnown();
        Book book = book(5, 2);
        when(transactionRepository.findByIdAndLibraryId(TRANSACTION_ID, LIBRARY_ID))
                .thenReturn(Optional.of(openLoan(book)));
        when(bookRepository.save(any(Book.class))).thenAnswer(i -> i.getArgument(0));
        when(transactionRepository.save(any(Transaction.class)))
                .thenThrow(clash(Transaction.class, TRANSACTION_ID));

        assertThatThrownBy(() -> transactionService.returnBook(TRANSACTION_ID, CALLER))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
    }

    // ---------- transaction boundaries ----------

    @Test
    void updateBookIsTransactional() throws Exception {
        // It reads both copy counts, derives what is on loan from the
        // difference, then writes both back. Outside a transaction that read and
        // that write are two separate units and the difference can go stale.
        assertThat(BookService.class
                .getMethod("updateBook", Long.class, BookRequest.class, String.class)
                .getAnnotation(Transactional.class))
                .as("the read-derive-write in updateBook must be one unit")
                .isNotNull();
    }

    @Test
    void issueAndReturnRemainTransactional() throws Exception {
        assertThat(TransactionService.class
                .getMethod("issueBook", Long.class, Long.class, String.class, LocalDate.class)
                .getAnnotation(Transactional.class))
                .isNotNull();
        assertThat(TransactionService.class
                .getMethod("returnBook", Long.class, String.class)
                .getAnnotation(Transactional.class))
                .isNotNull();
    }

    // ---------- existing behaviour, unchanged when nothing clashes ----------

    @Test
    void issueStillDecrementsExactlyOnceWhenNothingClashes() {
        callerIsKnown();
        Book book = book(3, 3);
        when(bookRepository.findByIdAndLibraryId(BOOK_ID, LIBRARY_ID)).thenReturn(Optional.of(book));
        memberIsKnown();
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(i -> i.getArgument(0));

        TransactionResponse response = transactionService.issueBook(BOOK_ID, MEMBER_ID, CALLER, DUE_DATE);

        assertThat(response.getStatus()).isEqualTo(TransactionStatus.ISSUED);
        assertThat(book.getAvailableCopies()).isEqualTo(2);
        assertThat(book.getTotalCopies()).isEqualTo(3);
    }

    @Test
    void returnStillRestoresACopyWhenNothingClashes() {
        callerIsKnown();
        Book book = book(5, 2);
        when(transactionRepository.findByIdAndLibraryId(TRANSACTION_ID, LIBRARY_ID))
                .thenReturn(Optional.of(openLoan(book)));
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(i -> i.getArgument(0));

        TransactionResponse response = transactionService.returnBook(TRANSACTION_ID, CALLER);

        assertThat(response.getStatus()).isEqualTo(TransactionStatus.RETURNED);
        assertThat(response.getReturnDate()).isEqualTo(LocalDate.now());
        assertThat(book.getAvailableCopies()).isEqualTo(3);
    }

    @Test
    void updateBookStillRecomputesAvailabilityWhenNothingClashes() {
        // Step 88's rule, unchanged: 10 total / 7 available means 3 on loan, so
        // a new total of 12 leaves 9 on the shelf.
        callerIsKnown();
        when(bookRepository.findByIdAndLibraryId(BOOK_ID, LIBRARY_ID))
                .thenReturn(Optional.of(book(10, 7)));
        when(bookRepository.findByIsbnAndLibraryId(anyString(), eq(LIBRARY_ID)))
                .thenReturn(Optional.empty());
        when(bookRepository.save(any(Book.class))).thenAnswer(i -> i.getArgument(0));

        BookResponse response = bookService().updateBook(BOOK_ID, request(12), CALLER);

        assertThat(response.getTotalCopies()).isEqualTo(12);
        assertThat(response.getAvailableCopies()).isEqualTo(9);
    }

    // ---------- how the clash reaches the caller ----------

    @Test
    void aVersionClashIsReportedAs409() {
        ResponseEntity<GlobalExceptionHandler.ErrorResponse> response =
                new GlobalExceptionHandler().handleOptimisticLockingFailure(clash(Book.class, BOOK_ID));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo(409);
        assertThat(response.getBody().message())
                .isEqualTo("The resource was modified by another request. Please try again.");
    }

    @Test
    void theClashMessageDescribesNoRow() {
        // A losing writer learns that it lost, and nothing about the row it did
        // not manage to write.
        GlobalExceptionHandler.ErrorResponse body =
                new GlobalExceptionHandler().handleOptimisticLockingFailure(
                        clash(Book.class, BOOK_ID)).getBody();

        assertThat(body).isNotNull();
        assertThat(body.message())
                .doesNotContain(String.valueOf(BOOK_ID))
                .doesNotContain("books")
                .doesNotContain("version")
                .doesNotContain("Book");
        assertThat(body.timestamp()).isNotNull();
    }
}
