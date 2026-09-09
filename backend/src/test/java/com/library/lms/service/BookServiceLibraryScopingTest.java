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
import com.library.lms.exception.BookNotFoundException;
import com.library.lms.exception.CategoryNotFoundException;
import com.library.lms.exception.UserNotFoundException;
import com.library.lms.repository.BookRepository;
import com.library.lms.repository.CategoryRepository;
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
        request.setAvailableCopies(3);
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
        when(bookRepository.findByIsbn(anyString())).thenReturn(Optional.empty());
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
        when(bookRepository.findByIsbn(anyString())).thenReturn(Optional.empty());
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
        when(bookRepository.findByIsbn(anyString())).thenReturn(Optional.empty());
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
        when(bookRepository.findByIsbn(anyString())).thenReturn(Optional.empty());
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
}
