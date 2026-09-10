package com.library.lms.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

import com.library.lms.dto.BookRequest;
import com.library.lms.dto.BookResponse;
import com.library.lms.entity.Book;
import com.library.lms.entity.Category;
import com.library.lms.entity.Library;
import com.library.lms.entity.Role;
import com.library.lms.entity.User;
import com.library.lms.exception.BookInUseException;
import com.library.lms.exception.BookNotFoundException;
import com.library.lms.exception.GlobalExceptionHandler;
import com.library.lms.exception.CategoryNotFoundException;
import com.library.lms.exception.DuplicateIsbnException;
import com.library.lms.exception.InvalidCopyCountException;
import com.library.lms.exception.UserNotFoundException;
import com.library.lms.repository.BookRepository;
import com.library.lms.repository.CategoryRepository;
import com.library.lms.repository.TransactionRepository;
import com.library.lms.repository.UserRepository;

/**
 * Guards the library boundary on every Book operation.
 *
 * <p>Every collaborator is mocked, so no Spring context starts and no row is
 * read or written.</p>
 *
 * <p>Two properties carry most of the weight. First, a caller must never reach
 * another library's book - and must not be able to tell "that book belongs to
 * someone else" from "that book does not exist", or the id becomes a directory
 * of a neighbour's stock. Second, a book must never be filed under another
 * library's category, which is the one cross-tenant <i>write</i> the old code
 * allowed.</p>
 */
@ExtendWith(MockitoExtension.class)
class BookServiceLibraryScopingTest {

    private static final String CALLER = "a-librarian";

    private static final Long OWN_LIBRARY_ID = 1L;

    private static final Long OTHER_LIBRARY_ID = 2L;

    private static final Long BOOK_ID = 7L;

    private static final Long CATEGORY_ID = 3L;

    @Mock
    private BookRepository bookRepository;

    @Mock
    private CategoryRepository categoryRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private TransactionRepository transactionRepository;

    @InjectMocks
    private BookService bookService;

    private static Library library(Long id) {
        Library library = new Library();
        library.setId(id);
        library.setName("Library " + id);
        return library;
    }

    private static Category category(Long id, Long libraryId) {
        Category category = new Category();
        category.setId(id);
        category.setName("Fiction");
        category.setLibrary(library(libraryId));
        return category;
    }

    private static Book book(Long id, String title, Long libraryId) {
        Book book = new Book();
        book.setId(id);
        book.setTitle(title);
        book.setAuthor("An Author");
        book.setIsbn("978000000000" + id);
        book.setTotalCopies(3);
        book.setAvailableCopies(3);
        book.setLibrary(library(libraryId));
        return book;
    }

    /** A book with explicit copy counts, so issued = total - available. */
    private static Book stocked(Long id, int totalCopies, int availableCopies) {
        Book book = book(id, "Stocked", OWN_LIBRARY_ID);
        book.setTotalCopies(totalCopies);
        book.setAvailableCopies(availableCopies);
        return book;
    }

    private static BookRequest requestWithTotal(int totalCopies) {
        BookRequest request = request(null);
        request.setTotalCopies(totalCopies);
        return request;
    }

    private void callerIsInOwnLibrary() {
        User user = new User();
        user.setId(10L);
        user.setUsername(CALLER);
        user.setRole(Role.ROLE_LIBRARIAN);
        user.setLibrary(library(OWN_LIBRARY_ID));
        when(userRepository.findByUsername(CALLER)).thenReturn(Optional.of(user));
    }

    private static BookRequest request(Long categoryId) {
        BookRequest request = new BookRequest();
        request.setTitle("A Title");
        request.setAuthor("An Author");
        request.setIsbn("9780000000999");
        request.setCategoryId(categoryId);
        request.setTotalCopies(3);
        return request;
    }

    // ---------- the request carries no tenant ----------

    @Test
    void bookRequestCarriesNoLibraryId() {
        assertThat(java.util.Arrays.stream(BookRequest.class.getDeclaredFields())
                .map(java.lang.reflect.Field::getName))
                .as("a library must never be selectable by the client")
                .doesNotContain("libraryId", "library");

        assertThat(java.util.Arrays.stream(BookRequest.class.getMethods())
                .map(java.lang.reflect.Method::getName))
                .doesNotContain("getLibraryId", "setLibraryId");
    }

    // ---------- reads ----------

    @Test
    void listAppliesTheLibraryPredicateToEveryPagedQuery() {
        callerIsInOwnLibrary();
        when(bookRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(book(1L, "Owned", OWN_LIBRARY_ID))));

        bookService.getAllBooks(0, 10, "id", "asc", null, null, CALLER);

        // The unscoped overloads must never be reached.
        verify(bookRepository, never()).findAll();
        verify(bookRepository).findAll(any(Specification.class), any(Pageable.class));
    }

    @Test
    void searchAppliesTheLibraryPredicate() {
        callerIsInOwnLibrary();
        when(bookRepository.findAll(any(Specification.class)))
                .thenReturn(List.of(book(1L, "Owned", OWN_LIBRARY_ID)));

        List<BookResponse> results = bookService.searchBooks("owned", CALLER);

        assertThat(results).hasSize(1);
        verify(bookRepository).findAll(any(Specification.class));
        verify(bookRepository, never()).findAll();
    }

    @Test
    void categoryListingIsScopedToTheCallersLibrary() {
        callerIsInOwnLibrary();
        when(bookRepository.findByLibraryIdAndCategoryName(OWN_LIBRARY_ID, "Fiction"))
                .thenReturn(List.of(book(1L, "Owned", OWN_LIBRARY_ID)));

        List<BookResponse> results = bookService.getBooksByCategory("Fiction", CALLER);

        assertThat(results).hasSize(1);
        verify(bookRepository).findByLibraryIdAndCategoryName(OWN_LIBRARY_ID, "Fiction");
    }

    @Test
    void getByIdReturnsTheCallersOwnBook() {
        callerIsInOwnLibrary();
        when(bookRepository.findByIdAndLibraryId(BOOK_ID, OWN_LIBRARY_ID))
                .thenReturn(Optional.of(book(BOOK_ID, "Owned", OWN_LIBRARY_ID)));

        BookResponse response = bookService.getBookById(BOOK_ID, CALLER);

        assertThat(response.getId()).isEqualTo(BOOK_ID);
        assertThat(response.getTitle()).isEqualTo("Owned");
    }

    @Test
    void getByIdIsRefusedForAnotherLibrarysBook() {
        callerIsInOwnLibrary();
        when(bookRepository.findByIdAndLibraryId(BOOK_ID, OWN_LIBRARY_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> bookService.getBookById(BOOK_ID, CALLER))
                .isInstanceOf(BookNotFoundException.class);

        // Never loaded unscoped, so the foreign row is not even read.
        verify(bookRepository, never()).findById(anyLong());
    }

    @Test
    void aForeignBookIsIndistinguishableFromOneThatDoesNotExist() {
        callerIsInOwnLibrary();
        when(bookRepository.findByIdAndLibraryId(anyLong(), eq(OWN_LIBRARY_ID))).thenReturn(Optional.empty());

        Throwable foreign = catchThrowable(() -> bookService.getBookById(BOOK_ID, CALLER));
        Throwable missing = catchThrowable(() -> bookService.getBookById(4242L, CALLER));

        assertThat(foreign).isInstanceOf(BookNotFoundException.class);
        assertThat(missing).isInstanceOf(BookNotFoundException.class);
        assertThat(missing.getClass()).isEqualTo(foreign.getClass());
        assertThat(foreign.getMessage()).doesNotContainIgnoringCase("library");
    }

    // ---------- create ----------

    @Test
    void createAssignsTheCallersLibraryAndNeverAClientValue() {
        callerIsInOwnLibrary();
        when(bookRepository.findByIsbnAndLibraryId(anyString(), eq(OWN_LIBRARY_ID)))
                .thenReturn(Optional.empty());
        when(categoryRepository.findByIdAndLibraryId(CATEGORY_ID, OWN_LIBRARY_ID))
                .thenReturn(Optional.of(category(CATEGORY_ID, OWN_LIBRARY_ID)));
        when(bookRepository.save(any(Book.class))).thenAnswer(i -> i.getArgument(0));

        bookService.createBook(request(CATEGORY_ID), CALLER);

        ArgumentCaptor<Book> saved = ArgumentCaptor.forClass(Book.class);
        verify(bookRepository).save(saved.capture());
        assertThat(saved.getValue().getLibrary().getId()).isEqualTo(OWN_LIBRARY_ID);
    }

    @Test
    void createRefusesACategoryFromAnotherLibrary() {
        // The cross-tenant *write* the old global findById allowed.
        callerIsInOwnLibrary();
        when(bookRepository.findByIsbnAndLibraryId(anyString(), eq(OWN_LIBRARY_ID)))
                .thenReturn(Optional.empty());
        when(categoryRepository.findByIdAndLibraryId(CATEGORY_ID, OWN_LIBRARY_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> bookService.createBook(request(CATEGORY_ID), CALLER))
                .isInstanceOf(CategoryNotFoundException.class);

        verify(categoryRepository, never()).findById(anyLong());
        verify(bookRepository, never()).save(any(Book.class));
    }

    // ---------- update ----------

    @Test
    void updateIsRefusedForAnotherLibrarysBook() {
        callerIsInOwnLibrary();
        when(bookRepository.findByIdAndLibraryId(BOOK_ID, OWN_LIBRARY_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> bookService.updateBook(BOOK_ID, request(null), CALLER))
                .isInstanceOf(BookNotFoundException.class);

        verify(bookRepository, never()).save(any(Book.class));
    }

    @Test
    void updateRefusesACategoryFromAnotherLibrary() {
        callerIsInOwnLibrary();
        when(bookRepository.findByIdAndLibraryId(BOOK_ID, OWN_LIBRARY_ID))
                .thenReturn(Optional.of(book(BOOK_ID, "Owned", OWN_LIBRARY_ID)));
        when(bookRepository.findByIsbnAndLibraryId(anyString(), eq(OWN_LIBRARY_ID)))
                .thenReturn(Optional.empty());
        when(categoryRepository.findByIdAndLibraryId(CATEGORY_ID, OWN_LIBRARY_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> bookService.updateBook(BOOK_ID, request(CATEGORY_ID), CALLER))
                .isInstanceOf(CategoryNotFoundException.class);

        verify(bookRepository, never()).save(any(Book.class));
    }

    @Test
    void updateSucceedsForTheCallersOwnBook() {
        callerIsInOwnLibrary();
        Book own = book(BOOK_ID, "Old Title", OWN_LIBRARY_ID);
        when(bookRepository.findByIdAndLibraryId(BOOK_ID, OWN_LIBRARY_ID)).thenReturn(Optional.of(own));
        when(bookRepository.findByIsbnAndLibraryId(anyString(), eq(OWN_LIBRARY_ID)))
                .thenReturn(Optional.empty());
        when(bookRepository.save(any(Book.class))).thenAnswer(i -> i.getArgument(0));

        BookResponse response = bookService.updateBook(BOOK_ID, request(null), CALLER);

        assertThat(response.getTitle()).isEqualTo("A Title");
    }

    // ---------- delete ----------

    @Test
    void deleteIsRefusedForAnotherLibrarysBook() {
        callerIsInOwnLibrary();
        when(bookRepository.findByIdAndLibraryId(BOOK_ID, OWN_LIBRARY_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> bookService.deleteBook(BOOK_ID, CALLER))
                .isInstanceOf(BookNotFoundException.class);

        verify(bookRepository, never()).delete(any(Book.class));
        verify(bookRepository, never()).deleteById(anyLong());
    }

    @Test
    void deleteSucceedsForTheCallersOwnBook() {
        callerIsInOwnLibrary();
        Book own = book(BOOK_ID, "Owned", OWN_LIBRARY_ID);
        when(bookRepository.findByIdAndLibraryId(BOOK_ID, OWN_LIBRARY_ID)).thenReturn(Optional.of(own));

        bookService.deleteBook(BOOK_ID, CALLER);

        verify(bookRepository).delete(own);
    }

    @Test
    void deleteSucceedsWhenTheBookHasNoLoanHistory() {
        callerIsInOwnLibrary();
        Book own = book(BOOK_ID, "Never Borrowed", OWN_LIBRARY_ID);
        when(bookRepository.findByIdAndLibraryId(BOOK_ID, OWN_LIBRARY_ID)).thenReturn(Optional.of(own));
        when(transactionRepository.existsByBookIdAndLibraryId(BOOK_ID, OWN_LIBRARY_ID)).thenReturn(false);

        bookService.deleteBook(BOOK_ID, CALLER);

        verify(bookRepository).delete(own);
    }

    @Test
    void deleteIsRefusedWhenTheBookHasLoanHistory() {
        callerIsInOwnLibrary();
        Book own = book(BOOK_ID, "Once Borrowed", OWN_LIBRARY_ID);
        when(bookRepository.findByIdAndLibraryId(BOOK_ID, OWN_LIBRARY_ID)).thenReturn(Optional.of(own));
        when(transactionRepository.existsByBookIdAndLibraryId(BOOK_ID, OWN_LIBRARY_ID)).thenReturn(true);

        assertThatThrownBy(() -> bookService.deleteBook(BOOK_ID, CALLER))
                .isInstanceOf(BookInUseException.class)
                .hasMessageContaining("Once Borrowed")
                .hasMessageContaining(String.valueOf(BOOK_ID));

        // The history is what is being protected, so nothing may be removed.
        verify(bookRepository, never()).delete(any(Book.class));
        verify(bookRepository, never()).deleteById(anyLong());
    }

    @Test
    void aReturnedLoanStillBlocksTheDelete() {
        // The policy is deliberately "any history", not "any open loan": a
        // returned loan is exactly the audit record worth keeping.
        callerIsInOwnLibrary();
        when(bookRepository.findByIdAndLibraryId(BOOK_ID, OWN_LIBRARY_ID))
                .thenReturn(Optional.of(book(BOOK_ID, "Returned Long Ago", OWN_LIBRARY_ID)));
        when(transactionRepository.existsByBookIdAndLibraryId(BOOK_ID, OWN_LIBRARY_ID)).thenReturn(true);

        assertThatThrownBy(() -> bookService.deleteBook(BOOK_ID, CALLER))
                .isInstanceOf(BookInUseException.class);

        verify(bookRepository, never()).delete(any(Book.class));
    }

    @Test
    void theHistoryCheckIsScopedToTheCallersLibrary() {
        callerIsInOwnLibrary();
        when(bookRepository.findByIdAndLibraryId(BOOK_ID, OWN_LIBRARY_ID))
                .thenReturn(Optional.of(book(BOOK_ID, "Owned", OWN_LIBRARY_ID)));
        when(transactionRepository.existsByBookIdAndLibraryId(BOOK_ID, OWN_LIBRARY_ID)).thenReturn(false);

        bookService.deleteBook(BOOK_ID, CALLER);

        // Never the caller's id, never another library's.
        verify(transactionRepository).existsByBookIdAndLibraryId(BOOK_ID, OWN_LIBRARY_ID);
        verify(transactionRepository, never())
                .existsByBookIdAndLibraryId(BOOK_ID, OTHER_LIBRARY_ID);
    }

    @Test
    void aCrossLibraryDeleteNeverReachesTheHistoryCheck() {
        // Tenant scoping still comes first: a book in another library is
        // refused as missing, before any question is asked about its loans.
        callerIsInOwnLibrary();
        when(bookRepository.findByIdAndLibraryId(BOOK_ID, OWN_LIBRARY_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> bookService.deleteBook(BOOK_ID, CALLER))
                .isInstanceOf(BookNotFoundException.class);

        verify(transactionRepository, never()).existsByBookIdAndLibraryId(anyLong(), anyLong());
        verify(bookRepository, never()).delete(any(Book.class));
    }

    @Test
    void aBookInUseRefusalIsReportedAs409() {
        GlobalExceptionHandler.ErrorResponse body = new GlobalExceptionHandler()
                .handleBookInUse(new BookInUseException(BOOK_ID, "Once Borrowed"))
                .getBody();

        assertThat(body).isNotNull();
        assertThat(body.status()).isEqualTo(409);
        assertThat(body.message())
                .contains("Once Borrowed")
                .doesNotContain("transactions.book_id")
                .doesNotContain("constraint")
                .doesNotContain("SQL");
    }

    // ---------- identity ----------

    @Test
    void anUnresolvableAuthenticatedNameNeverReachesTheBookTable() {
        when(userRepository.findByUsername("ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> bookService.getBookById(BOOK_ID, "ghost"))
                .isInstanceOf(UserNotFoundException.class);

        verify(bookRepository, never()).findByIdAndLibraryId(anyLong(), anyLong());
    }

    @Test
    void theLibraryUsedIsAlwaysTheCallersOwn() {
        callerIsInOwnLibrary();
        when(bookRepository.findByIdAndLibraryId(BOOK_ID, OWN_LIBRARY_ID))
                .thenReturn(Optional.of(book(BOOK_ID, "Owned", OWN_LIBRARY_ID)));

        bookService.getBookById(BOOK_ID, CALLER);

        verify(bookRepository).findByIdAndLibraryId(BOOK_ID, OWN_LIBRARY_ID);
        verify(bookRepository, never()).findByIdAndLibraryId(anyLong(), eq(OTHER_LIBRARY_ID));
    }

    // ---------- library-scoped ISBN uniqueness ----------

    @Test
    void createRejectsADuplicateIsbnWithinTheSameLibrary() {
        callerIsInOwnLibrary();
        when(bookRepository.findByIsbnAndLibraryId("9780000000999", OWN_LIBRARY_ID))
                .thenReturn(Optional.of(book(99L, "Already Stocked", OWN_LIBRARY_ID)));

        assertThatThrownBy(() -> bookService.createBook(request(null), CALLER))
                .isInstanceOf(DuplicateIsbnException.class);

        verify(bookRepository, never()).save(any(Book.class));
    }

    @Test
    void createAllowsTheSameIsbnInADifferentLibrary() {
        // The point of the change: library 2 stocking a title library 1 already
        // holds is ordinary, and its own scoped check comes back clean.
        User other = new User();
        other.setId(20L);
        other.setUsername("other-librarian");
        other.setRole(Role.ROLE_LIBRARIAN);
        other.setLibrary(library(OTHER_LIBRARY_ID));
        when(userRepository.findByUsername("other-librarian")).thenReturn(Optional.of(other));
        when(bookRepository.findByIsbnAndLibraryId("9780000000999", OTHER_LIBRARY_ID))
                .thenReturn(Optional.empty());
        when(bookRepository.save(any(Book.class))).thenAnswer(i -> i.getArgument(0));

        BookResponse response = bookService.createBook(request(null), "other-librarian");

        assertThat(response.getIsbn()).isEqualTo("9780000000999");
    }

    @Test
    void createChecksIsbnWithTheScopedLookupNotAGlobalOne() {
        callerIsInOwnLibrary();
        when(bookRepository.findByIsbnAndLibraryId("9780000000999", OWN_LIBRARY_ID))
                .thenReturn(Optional.empty());
        when(bookRepository.save(any(Book.class))).thenAnswer(i -> i.getArgument(0));

        bookService.createBook(request(null), CALLER);

        verify(bookRepository).findByIsbnAndLibraryId("9780000000999", OWN_LIBRARY_ID);
        verify(bookRepository, never()).findByIsbnAndLibraryId(anyString(), eq(OTHER_LIBRARY_ID));
    }

    @Test
    void updateRejectsAnIsbnHeldByAnotherBookInTheSameLibrary() {
        callerIsInOwnLibrary();
        when(bookRepository.findByIdAndLibraryId(BOOK_ID, OWN_LIBRARY_ID))
                .thenReturn(Optional.of(book(BOOK_ID, "Being Edited", OWN_LIBRARY_ID)));
        when(bookRepository.findByIsbnAndLibraryId("9780000000999", OWN_LIBRARY_ID))
                .thenReturn(Optional.of(book(99L, "A Different Book", OWN_LIBRARY_ID)));

        assertThatThrownBy(() -> bookService.updateBook(BOOK_ID, request(null), CALLER))
                .isInstanceOf(DuplicateIsbnException.class);

        verify(bookRepository, never()).save(any(Book.class));
    }

    @Test
    void updateAllowsAnIsbnThatOnlyAnotherLibraryHolds() {
        // The scoped lookup simply does not see the other library's row, so the
        // rename goes through.
        callerIsInOwnLibrary();
        when(bookRepository.findByIdAndLibraryId(BOOK_ID, OWN_LIBRARY_ID))
                .thenReturn(Optional.of(book(BOOK_ID, "Being Edited", OWN_LIBRARY_ID)));
        when(bookRepository.findByIsbnAndLibraryId("9780000000999", OWN_LIBRARY_ID))
                .thenReturn(Optional.empty());
        when(bookRepository.save(any(Book.class))).thenAnswer(i -> i.getArgument(0));

        BookResponse response = bookService.updateBook(BOOK_ID, request(null), CALLER);

        assertThat(response.getIsbn()).isEqualTo("9780000000999");
    }

    @Test
    void updateLetsABookKeepItsOwnIsbn() {
        // The scoped lookup returns the very row being edited; excluding it by id
        // is what stops a no-op rename from reporting a duplicate against itself.
        callerIsInOwnLibrary();
        Book editing = book(BOOK_ID, "Being Edited", OWN_LIBRARY_ID);
        when(bookRepository.findByIdAndLibraryId(BOOK_ID, OWN_LIBRARY_ID)).thenReturn(Optional.of(editing));
        when(bookRepository.findByIsbnAndLibraryId("9780000000999", OWN_LIBRARY_ID))
                .thenReturn(Optional.of(editing));
        when(bookRepository.save(any(Book.class))).thenAnswer(i -> i.getArgument(0));

        BookResponse response = bookService.updateBook(BOOK_ID, request(null), CALLER);

        assertThat(response.getIsbn()).isEqualTo("9780000000999");
    }

    @Test
    void isbnValidationAlwaysUsesTheCallersOwnLibrary() {
        callerIsInOwnLibrary();
        when(bookRepository.findByIdAndLibraryId(BOOK_ID, OWN_LIBRARY_ID))
                .thenReturn(Optional.of(book(BOOK_ID, "Being Edited", OWN_LIBRARY_ID)));
        when(bookRepository.findByIsbnAndLibraryId("9780000000999", OWN_LIBRARY_ID))
                .thenReturn(Optional.empty());
        when(bookRepository.save(any(Book.class))).thenAnswer(i -> i.getArgument(0));

        bookService.updateBook(BOOK_ID, request(null), CALLER);

        verify(bookRepository).findByIsbnAndLibraryId("9780000000999", OWN_LIBRARY_ID);
    }

    // ---------- copy-count safety ----------

    @Test
    void bookRequestCarriesNoAvailableCopies() {
        // availableCopies is a running total the system maintains, not something
        // a routine edit may overwrite.
        assertThat(java.util.Arrays.stream(BookRequest.class.getDeclaredFields())
                .map(java.lang.reflect.Field::getName))
                .as("available copies must not be client-supplied")
                .doesNotContain("availableCopies")
                .contains("totalCopies");

        assertThat(java.util.Arrays.stream(BookRequest.class.getMethods())
                .map(java.lang.reflect.Method::getName))
                .doesNotContain("getAvailableCopies", "setAvailableCopies");
    }

    @Test
    void createPutsEveryCopyOnTheShelf() {
        callerIsInOwnLibrary();
        when(bookRepository.findByIsbnAndLibraryId(anyString(), eq(OWN_LIBRARY_ID)))
                .thenReturn(Optional.empty());
        when(bookRepository.save(any(Book.class))).thenAnswer(i -> i.getArgument(0));

        BookResponse response = bookService.createBook(requestWithTotal(10), CALLER);

        assertThat(response.getTotalCopies()).isEqualTo(10);
        assertThat(response.getAvailableCopies())
                .as("a new title has nothing on loan yet")
                .isEqualTo(10);
    }

    @Test
    void createDerivesAvailabilityRatherThanTakingIt() {
        callerIsInOwnLibrary();
        when(bookRepository.findByIsbnAndLibraryId(anyString(), eq(OWN_LIBRARY_ID)))
                .thenReturn(Optional.empty());
        when(bookRepository.save(any(Book.class))).thenAnswer(i -> i.getArgument(0));

        bookService.createBook(requestWithTotal(4), CALLER);

        ArgumentCaptor<Book> saved = ArgumentCaptor.forClass(Book.class);
        verify(bookRepository).save(saved.capture());
        assertThat(saved.getValue().getAvailableCopies()).isEqualTo(saved.getValue().getTotalCopies());
    }

    @Test
    void raisingTheTotalPutsTheNewCopiesOnTheShelf() {
        // old total 10, available 7 -> 3 on loan. New total 12 -> 9 available.
        callerIsInOwnLibrary();
        Book existing = stocked(BOOK_ID, 10, 7);
        when(bookRepository.findByIdAndLibraryId(BOOK_ID, OWN_LIBRARY_ID)).thenReturn(Optional.of(existing));
        when(bookRepository.findByIsbnAndLibraryId(anyString(), eq(OWN_LIBRARY_ID)))
                .thenReturn(Optional.empty());
        when(bookRepository.save(any(Book.class))).thenAnswer(i -> i.getArgument(0));

        BookResponse response = bookService.updateBook(BOOK_ID, requestWithTotal(12), CALLER);

        assertThat(response.getTotalCopies()).isEqualTo(12);
        assertThat(response.getAvailableCopies()).isEqualTo(9);
    }

    @Test
    void loweringTheTotalTakesCopiesOffTheShelfOnly() {
        // old total 10, available 7 -> 3 on loan. New total 8 -> 5 available.
        callerIsInOwnLibrary();
        Book existing = stocked(BOOK_ID, 10, 7);
        when(bookRepository.findByIdAndLibraryId(BOOK_ID, OWN_LIBRARY_ID)).thenReturn(Optional.of(existing));
        when(bookRepository.findByIsbnAndLibraryId(anyString(), eq(OWN_LIBRARY_ID)))
                .thenReturn(Optional.empty());
        when(bookRepository.save(any(Book.class))).thenAnswer(i -> i.getArgument(0));

        BookResponse response = bookService.updateBook(BOOK_ID, requestWithTotal(8), CALLER);

        assertThat(response.getTotalCopies()).isEqualTo(8);
        assertThat(response.getAvailableCopies()).isEqualTo(5);
    }

    @Test
    void aTotalBelowTheIssuedCountIsRejected() {
        // old total 10, available 7 -> 3 on loan. New total 2 cannot hold them.
        callerIsInOwnLibrary();
        Book existing = stocked(BOOK_ID, 10, 7);
        when(bookRepository.findByIdAndLibraryId(BOOK_ID, OWN_LIBRARY_ID)).thenReturn(Optional.of(existing));
        when(bookRepository.findByIsbnAndLibraryId(anyString(), eq(OWN_LIBRARY_ID)))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> bookService.updateBook(BOOK_ID, requestWithTotal(2), CALLER))
                .isInstanceOf(InvalidCopyCountException.class)
                .hasMessageContaining("2")
                .hasMessageContaining("3");

        verify(bookRepository, never()).save(any(Book.class));
        assertThat(existing.getTotalCopies()).as("left untouched on refusal").isEqualTo(10);
        assertThat(existing.getAvailableCopies()).isEqualTo(7);
    }

    @Test
    void aTotalExactlyEqualToTheIssuedCountIsAllowed() {
        // The boundary: 3 on loan, new total 3 -> 0 on the shelf, never negative.
        callerIsInOwnLibrary();
        Book existing = stocked(BOOK_ID, 10, 7);
        when(bookRepository.findByIdAndLibraryId(BOOK_ID, OWN_LIBRARY_ID)).thenReturn(Optional.of(existing));
        when(bookRepository.findByIsbnAndLibraryId(anyString(), eq(OWN_LIBRARY_ID)))
                .thenReturn(Optional.empty());
        when(bookRepository.save(any(Book.class))).thenAnswer(i -> i.getArgument(0));

        BookResponse response = bookService.updateBook(BOOK_ID, requestWithTotal(3), CALLER);

        assertThat(response.getTotalCopies()).isEqualTo(3);
        assertThat(response.getAvailableCopies()).isZero();
    }

    @Test
    void updateNeverCopiesAvailabilityFromTheRequest() {
        // Availability is computed from the stored counts alone. The request has
        // no availableCopies to read, and the saved value proves it was derived.
        callerIsInOwnLibrary();
        Book existing = stocked(BOOK_ID, 10, 7);
        when(bookRepository.findByIdAndLibraryId(BOOK_ID, OWN_LIBRARY_ID)).thenReturn(Optional.of(existing));
        when(bookRepository.findByIsbnAndLibraryId(anyString(), eq(OWN_LIBRARY_ID)))
                .thenReturn(Optional.empty());
        when(bookRepository.save(any(Book.class))).thenAnswer(i -> i.getArgument(0));

        bookService.updateBook(BOOK_ID, requestWithTotal(12), CALLER);

        ArgumentCaptor<Book> saved = ArgumentCaptor.forClass(Book.class);
        verify(bookRepository).save(saved.capture());
        int issuedBefore = 10 - 7;
        assertThat(saved.getValue().getTotalCopies() - saved.getValue().getAvailableCopies())
                .as("the number on loan is preserved across the edit")
                .isEqualTo(issuedBefore);
    }
}
