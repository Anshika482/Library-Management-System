package com.library.lms.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.time.LocalDate;
import java.util.UUID;

import org.hibernate.Hibernate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.library.lms.dto.PagedResponse;
import com.library.lms.dto.TransactionResponse;
import com.library.lms.entity.Book;
import com.library.lms.entity.Library;
import com.library.lms.entity.Role;
import com.library.lms.entity.Transaction;
import com.library.lms.entity.TransactionStatus;
import com.library.lms.entity.User;
import com.library.lms.repository.BookRepository;
import com.library.lms.repository.LibraryRepository;
import com.library.lms.repository.TransactionRepository;
import com.library.lms.repository.UserRepository;

/**
 * Proves that reading a loan works against <b>detached</b> entities, using a real
 * Hibernate session rather than mocks.
 *
 * <p>Every other test in this project hands the service plain Java objects
 * through Mockito, so none of them can see a Hibernate proxy, let alone a dead
 * one. That leaves one question unanswered: the four read methods on
 * {@code TransactionService} are deliberately <b>not</b> {@code @Transactional},
 * and {@code spring.jpa.open-in-view} is false, so by the time
 * {@code toResponse} runs the entity has already been detached - its persistence
 * context closed when the repository call returned. Whether the association
 * getters still work at that point depends on Hibernate internals that no unit
 * test in this codebase can reach.</p>
 *
 * <p>Two of those getters are exercised here without being named directly:
 * {@code transaction.getBook().getId()} and {@code transaction.getUser().getId()}
 * inside the mapper, and {@code authenticatedUser.getLibrary().getId()} while
 * resolving the caller - the last of which is already a LAZY association today.
 * If a detached association getter were unsafe, these tests would fail with a
 * LazyInitializationException rather than an assertion error.</p>
 *
 * <p>The last test covers a different hazard on the same fault line: Lombok's
 * generated {@code toString()} reads every field it has not been told to skip,
 * and a Hibernate proxy answers {@code toString()} by initialising itself. On a
 * detached entity that throws. Every LAZY association in this project is
 * therefore {@code @ToString.Exclude}, and that test is what holds the two
 * newest ones to it.</p>
 *
 * <p><b>Deliberately absent:</b> no {@code @Transactional} on the class or the
 * methods, because one would keep a persistence context open for the whole test
 * and hide the very state being examined; no {@code Hibernate.initialize}; no
 * Mockito. The fixtures are saved through the real repositories, each save
 * committing and detaching in its own transaction, exactly as production does.</p>
 *
 * <p><b>Isolation:</b> this runs against its own schema, named below, created on
 * demand and never shared with the development database. {@code ddl-auto} stays
 * on {@code update} rather than {@code create-drop} on purpose - update can add
 * tables but can never drop one, so even a mistyped URL could not destroy data.
 * Every fixture is given a unique name per run, so repeated runs cannot collide
 * on a unique constraint and nothing ever needs deleting.</p>
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:mysql://localhost:3306/library_db_step104_it"
                + "?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true"
                + "&serverTimezone=UTC",
        "spring.jpa.hibernate.ddl-auto=update",
        "spring.jpa.open-in-view=false"
})
class TransactionServiceDetachedMappingTest {

    @Autowired
    private TransactionService transactionService;

    @Autowired
    private LibraryRepository libraryRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private BookRepository bookRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    /** Unique per run, so repeated runs never clash on a unique column. */
    private static String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    /**
     * Saves a library, a librarian, a book and one returned loan, each through
     * the real repository, and hands back the loan.
     *
     * <p>No transaction spans these calls, so every entity is committed and
     * detached before the next line runs - which is the point.</p>
     */
    private Transaction persistOneReturnedLoan(String suffix) {
        Library library = new Library();
        library.setName("Step104 Library " + suffix);
        library = libraryRepository.save(library);

        User librarian = new User();
        librarian.setUsername("step104-librarian-" + suffix);
        librarian.setEmail("step104-" + suffix + "@example.invalid");
        // Encoded with the application's own encoder rather than a literal, so
        // no credential-shaped constant is written down anywhere.
        librarian.setPassword(passwordEncoder.encode("integration-test-only-" + suffix));
        librarian.setFullName("Step 104 Librarian");
        librarian.setRole(Role.ROLE_LIBRARIAN);
        librarian.setLibrary(library);
        librarian = userRepository.save(librarian);

        Book book = new Book();
        book.setTitle("Step104 Title " + suffix);
        book.setAuthor("Step104 Author");
        book.setIsbn("IT-" + suffix);
        book.setLibrary(library);
        book.setTotalCopies(3);
        book.setAvailableCopies(3);
        book = bookRepository.save(book);

        Transaction loan = new Transaction();
        loan.setBook(book);
        loan.setUser(librarian);
        loan.setLibrary(library);
        loan.setIssueDate(LocalDate.now().minusDays(10));
        loan.setDueDate(LocalDate.now().minusDays(3));
        loan.setReturnDate(LocalDate.now().minusDays(1));
        loan.setFineAmount(2.50);
        loan.setStatus(TransactionStatus.RETURNED);

        return transactionRepository.save(loan);
    }

    @Test
    void aSingleLoanMapsCorrectlyFromADetachedEntity() {
        String suffix = unique();
        Transaction saved = persistOneReturnedLoan(suffix);

        // Real read path, no surrounding transaction: the service resolves the
        // caller, queries, and maps - the entity is detached before the mapper
        // touches its associations.
        TransactionResponse response = transactionService.getTransactionById(
                saved.getId(), "step104-librarian-" + suffix);

        assertThat(response.getId()).isEqualTo(saved.getId());
        assertThat(response.getBookId())
                .as("read from a detached association")
                .isEqualTo(saved.getBook().getId());
        assertThat(response.getUserId())
                .as("read from a detached association")
                .isEqualTo(saved.getUser().getId());
        assertThat(response.getIssueDate()).isEqualTo(LocalDate.now().minusDays(10));
        assertThat(response.getDueDate()).isEqualTo(LocalDate.now().minusDays(3));
        assertThat(response.getReturnDate()).isEqualTo(LocalDate.now().minusDays(1));
        assertThat(response.getFineAmount()).isEqualTo(2.50);
        assertThat(response.getStatus()).isEqualTo(TransactionStatus.RETURNED);
    }

    @Test
    void aPagedReadMapsCorrectlyFromDetachedEntities() {
        // The same question for the collection path, which maps through
        // toPagedResponse after the page has been returned and detached.
        String suffix = unique();
        Transaction saved = persistOneReturnedLoan(suffix);

        PagedResponse<TransactionResponse> page = transactionService.getTransactionsByStatus(
                TransactionStatus.RETURNED, 0, 10, "id", "asc",
                "step104-librarian-" + suffix);

        assertThat(page.getContent()).isNotEmpty();

        TransactionResponse mine = page.getContent().stream()
                .filter(t -> t.getId().equals(saved.getId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("the saved loan was not on the page"));

        assertThat(mine.getBookId()).isEqualTo(saved.getBook().getId());
        assertThat(mine.getUserId()).isEqualTo(saved.getUser().getId());
        assertThat(mine.getIssueDate()).isEqualTo(LocalDate.now().minusDays(10));
        assertThat(mine.getDueDate()).isEqualTo(LocalDate.now().minusDays(3));
        assertThat(mine.getReturnDate()).isEqualTo(LocalDate.now().minusDays(1));
        assertThat(mine.getFineAmount()).isEqualTo(2.50);
        assertThat(mine.getStatus()).isEqualTo(TransactionStatus.RETURNED);
    }

    @Test
    void theCallersLibraryIsResolvedFromADetachedAccount() {
        // Isolated statement of the association access that already relies on a
        // LAZY proxy today: the service reads the caller's library id from an
        // account loaded and detached by an earlier repository call. Reaching a
        // scoped result at all proves that getter survived detachment.
        String suffix = unique();
        Transaction saved = persistOneReturnedLoan(suffix);

        PagedResponse<TransactionResponse> page = transactionService.getTransactionsByBook(
                saved.getBook().getId(), 0, 10, "id", "asc",
                "step104-librarian-" + suffix);

        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getContent().get(0).getId()).isEqualTo(saved.getId());
        assertThat(page.getTotalElements()).isEqualTo(1);
    }

    @Test
    void toStringOnADetachedLoanDoesNotTouchItsAssociations() {
        String suffix = unique();
        Long loanId = persistOneReturnedLoan(suffix).getId();

        // Loaded through the real repository with no surrounding transaction, so
        // the row comes back detached and its LAZY associations are genuine,
        // uninitialised proxies - which the fixtures above are not, having been
        // assembled from real objects in this test.
        Transaction detached = transactionRepository.findById(loanId)
                .orElseThrow(() -> new AssertionError("the saved loan was not found"));

        // Hibernate.isInitialized only reports; it never initialises. Reading
        // the field returns the proxy without touching it either.
        assertThat(Hibernate.isInitialized(detached.getBook()))
                .as("precondition: book must still be an untouched proxy")
                .isFalse();
        assertThat(Hibernate.isInitialized(detached.getUser()))
                .as("precondition: user must still be an untouched proxy")
                .isFalse();

        assertThatCode(detached::toString)
                .as("a detached loan must be printable - this is what "
                        + "@ToString.Exclude on book and user buys")
                .doesNotThrowAnyException();

        // The stronger claim: toString did not merely survive, it never asked
        // for the associations at all. Had it read them, these would now be true
        // and the session is long closed, so it could not have succeeded.
        assertThat(Hibernate.isInitialized(detached.getBook()))
                .as("toString must not have initialised book")
                .isFalse();
        assertThat(Hibernate.isInitialized(detached.getUser()))
                .as("toString must not have initialised user")
                .isFalse();

        // It is still a useful toString: the scalars are all there.
        assertThat(detached.toString())
                .contains("id=" + loanId)
                .contains("RETURNED")
                .doesNotContain("Book(")
                .doesNotContain("User(");
    }
}
