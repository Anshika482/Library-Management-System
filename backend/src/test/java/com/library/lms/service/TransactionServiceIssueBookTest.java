package com.library.lms.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
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
import com.library.lms.entity.Book;
import com.library.lms.entity.Library;
import com.library.lms.entity.Role;
import com.library.lms.entity.Transaction;
import com.library.lms.entity.TransactionStatus;
import com.library.lms.entity.User;
import com.library.lms.exception.BookNotAvailableException;
import com.library.lms.exception.BookNotFoundException;
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
}
