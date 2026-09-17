package com.library.lms.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.List;
import java.util.UUID;

import org.hibernate.Hibernate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.library.lms.dto.BookResponse;
import com.library.lms.dto.PagedResponse;
import com.library.lms.entity.Book;
import com.library.lms.entity.Category;
import com.library.lms.entity.Library;
import com.library.lms.entity.Role;
import com.library.lms.entity.User;
import com.library.lms.repository.BookRepository;
import com.library.lms.repository.CategoryRepository;
import com.library.lms.repository.LibraryRepository;
import com.library.lms.repository.UserRepository;

/**
 * Baseline for the four Book read paths against a real Hibernate session.
 *
 * <p>These exist because of one line in {@code BookService.toResponse}: it reads
 * {@code book.getCategory().getName()}, not just the id. That difference decides
 * everything. An id can be served from an uninitialised proxy - which is why the
 * Transaction associations could be made LAZY safely - but a name cannot. It
 * forces the proxy to initialise, and by the time {@code toResponse} runs the
 * book is already detached: the read methods here are deliberately not
 * {@code @Transactional} and {@code open-in-view} is false.</p>
 *
 * <p>Every existing Book test is Mockito-based and hands the service real
 * objects, so none of them can see a proxy at all. Changing {@code Book.category}
 * to LAZY today would leave all of them green and break in production on the
 * first list request. These tests are the missing net: they pass against the
 * current EAGER mapping, recording the behaviour that must survive, and they are
 * what would fail loudly if the association were made lazy without an entity
 * graph to go with it.</p>
 *
 * <p>The last test covers the other half of making an association lazy: Lombok's
 * generated {@code toString()} reads every field it has not been told to skip,
 * and a proxy answers {@code toString()} by initialising itself, which on a
 * detached entity throws. Every LAZY association in this project is therefore
 * {@code @ToString.Exclude}, and that test is what holds {@code category} to
 * it.</p>
 *
 * <p><b>Deliberately absent:</b> no {@code @Transactional} on the class or any
 * method - one would hold a persistence context open and hide the detached state
 * entirely; no {@code Hibernate.initialize}; no Mockito. Fixtures are saved
 * through the real repositories, each save committing and detaching on its own.</p>
 *
 * <p><b>Isolation:</b> the same throwaway schema and settings as
 * {@code TransactionServiceDetachedMappingTest}, stated identically on purpose so
 * Spring treats the two as one context and starts it only once. {@code ddl-auto}
 * stays on {@code update}, which can add tables but never drop one. Each test
 * builds its own library with a unique suffix, so the list reads are scoped to
 * data this run created and cannot be perturbed by earlier runs.</p>
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:mysql://localhost:3306/library_db_step104_it"
                + "?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true"
                + "&serverTimezone=UTC",
        "spring.jpa.hibernate.ddl-auto=update",
        "spring.jpa.open-in-view=false"
})
class BookServiceDetachedCategoryMappingTest {

    @Autowired
    private BookService bookService;

    @Autowired
    private LibraryRepository libraryRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private BookRepository bookRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    /** One shelf's worth of fixtures, and the handles the tests need. */
    private record Shelf(String librarian, String categoryName, Long categoryId, List<Book> books) {
    }

    private static String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    /**
     * Saves a library, a librarian, a category and {@code bookCount} books, each
     * through the real repository so nothing spans a transaction.
     *
     * <p>The library is new every time, which is what makes the list reads
     * deterministic: they are scoped to the caller's library, so they can only
     * see what this call just created.</p>
     */
    private Shelf persistShelf(int bookCount) {
        String suffix = unique();

        Library library = new Library();
        library.setName("Step110 Library " + suffix);
        library = libraryRepository.save(library);

        User librarian = new User();
        librarian.setUsername("step110-librarian-" + suffix);
        librarian.setEmail("step110-" + suffix + "@example.invalid");
        librarian.setPassword(passwordEncoder.encode("integration-test-only-" + suffix));
        librarian.setFullName("Step 110 Librarian");
        librarian.setRole(Role.ROLE_LIBRARIAN);
        librarian.setLibrary(library);
        userRepository.save(librarian);

        Category category = new Category();
        category.setName("Step110 Cat " + suffix);
        category.setLibrary(library);
        category = categoryRepository.save(category);

        Book[] saved = new Book[bookCount];
        for (int i = 1; i <= bookCount; i++) {
            Book book = new Book();
            book.setTitle("Step110 Zyzzyva " + suffix + " #" + i);
            book.setAuthor("Step110 Author " + suffix);
            book.setIsbn("IT" + suffix + "-" + i);
            book.setCategory(category);
            book.setLibrary(library);
            book.setTotalCopies(4);
            book.setAvailableCopies(3);
            saved[i - 1] = bookRepository.save(book);
        }

        return new Shelf("step110-librarian-" + suffix, category.getName(), category.getId(),
                List.of(saved));
    }

    /** The assertion these tests exist for, plus the ordinary book fields. */
    private static void assertFullyMapped(BookResponse response, Shelf shelf, Book expected) {
        assertThat(response.getId()).isEqualTo(expected.getId());
        assertThat(response.getTitle()).isEqualTo(expected.getTitle());
        assertThat(response.getAuthor()).isEqualTo(expected.getAuthor());
        assertThat(response.getIsbn()).isEqualTo(expected.getIsbn());
        assertThat(response.getTotalCopies()).isEqualTo(4);
        assertThat(response.getAvailableCopies()).isEqualTo(3);

        assertThat(response.getCategoryId())
                .as("category id, readable from a proxy without initialising it")
                .isEqualTo(shelf.categoryId());
        assertThat(response.getCategoryName())
                .as("category NAME - this is the read that requires a live association")
                .isEqualTo(shelf.categoryName());
    }

    // ---------- getBookById ----------

    @Test
    void getBookByIdMapsTheCategoryNameFromADetachedBook() {
        Shelf shelf = persistShelf(1);
        Book expected = shelf.books().get(0);

        BookResponse response = bookService.getBookById(expected.getId(), shelf.librarian());

        assertFullyMapped(response, shelf, expected);
    }

    // ---------- getAllBooks (paged) ----------

    @Test
    void getAllBooksMapsCategoryNamesOnEveryRowOfThePage() {
        Shelf shelf = persistShelf(3);

        PagedResponse<BookResponse> page = bookService.getAllBooks(
                0, 2, "id", "asc", null, null, shelf.librarian());

        assertThat(page.getContent()).hasSize(2);
        assertFullyMapped(page.getContent().get(0), shelf, shelf.books().get(0));
        assertFullyMapped(page.getContent().get(1), shelf, shelf.books().get(1));
        assertThat(page.getContent())
                .as("every row must carry a category name, not just the first")
                .allSatisfy(book -> assertThat(book.getCategoryName()).isEqualTo(shelf.categoryName()));
    }

    @Test
    void getAllBooksReportsCorrectPagingTotals() {
        // Three books, two per page: the totals describe the whole library,
        // the content describes the slice.
        Shelf shelf = persistShelf(3);

        PagedResponse<BookResponse> firstPage = bookService.getAllBooks(
                0, 2, "id", "asc", null, null, shelf.librarian());

        assertThat(firstPage.getPage()).isZero();
        assertThat(firstPage.getSize()).isEqualTo(2);
        assertThat(firstPage.getTotalElements()).isEqualTo(3);
        assertThat(firstPage.getTotalPages()).isEqualTo(2);
        assertThat(firstPage.getContent()).hasSize(2);

        PagedResponse<BookResponse> secondPage = bookService.getAllBooks(
                1, 2, "id", "asc", null, null, shelf.librarian());

        assertThat(secondPage.getPage()).isEqualTo(1);
        assertThat(secondPage.getTotalElements()).isEqualTo(3);
        assertThat(secondPage.getTotalPages()).isEqualTo(2);
        assertThat(secondPage.getContent()).hasSize(1);
        assertFullyMapped(secondPage.getContent().get(0), shelf, shelf.books().get(2));
    }

    // ---------- searchBooks ----------

    @Test
    void searchBooksMapsTheCategoryNameFromDetachedBooks() {
        Shelf shelf = persistShelf(2);
        // The suffix appears in every title this run created and nowhere else.
        String keyword = shelf.books().get(0).getTitle().split(" ")[2];

        List<BookResponse> results =
                bookService.searchBooks(keyword, 0, 10, "id", "asc", shelf.librarian()).getContent();

        assertThat(results).hasSize(2);
        assertFullyMapped(results.get(0), shelf, shelf.books().get(0));
        assertThat(results)
                .allSatisfy(book -> assertThat(book.getCategoryName()).isEqualTo(shelf.categoryName()));
    }

    // ---------- getBooksByCategory ----------

    @Test
    void getBooksByCategoryMapsTheCategoryNameFromDetachedBooks() {
        Shelf shelf = persistShelf(2);

        List<BookResponse> results = bookService.getBooksByCategory(
                shelf.categoryName(), 0, 10, "id", "asc", shelf.librarian()).getContent();

        assertThat(results).hasSize(2);
        assertFullyMapped(results.get(0), shelf, shelf.books().get(0));
        assertFullyMapped(results.get(1), shelf, shelf.books().get(1));
    }

    // ---------- toString on a detached book ----------

    @Test
    void toStringOnADetachedBookDoesNotTouchItsCategory() {
        Shelf shelf = persistShelf(1);
        Long bookId = shelf.books().get(0).getId();

        // Loaded through plain findById, which is inherited from JpaRepository
        // and carries no entity graph. That matters: findByIdAndLibraryId would
        // fetch the category and leave nothing lazy to test.
        Book detached = bookRepository.findById(bookId)
                .orElseThrow(() -> new AssertionError("the saved book was not found"));

        // Hibernate.isInitialized only reports; it never initialises. Reading
        // the field returns the proxy without touching it either.
        assertThat(Hibernate.isInitialized(detached.getCategory()))
                .as("precondition: category must still be an untouched proxy")
                .isFalse();

        assertThatCode(detached::toString)
                .as("a detached book must be printable - this is what "
                        + "@ToString.Exclude on category buys")
                .doesNotThrowAnyException();

        // The stronger claim: toString did not merely survive, it never asked
        // for the category. Had it read it, this would now be true - and the
        // session is long closed, so it could not have succeeded.
        assertThat(Hibernate.isInitialized(detached.getCategory()))
                .as("toString must not have initialised category")
                .isFalse();

        assertThat(detached.toString())
                .doesNotContain("Category(")
                .contains("id=" + bookId)
                .contains(detached.getTitle());
    }
}
