package com.library.lms.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.library.lms.entity.Transaction;
import com.library.lms.entity.TransactionStatus;

/**
 * Data-access layer for {@link Transaction}.
 *
 * <p>Every declared query is scoped to a library, and that is the point. The
 * unscoped versions these replace - {@code findByBookId}, {@code findByUserId}
 * and {@code findByStatus} - answered across every tenant at once, so a caller
 * in one library could read another library's loans just by asking. They were
 * removed rather than kept alongside these: a global method that still compiles
 * is a global method somebody will eventually call.</p>
 *
 * <p>The library is always the second parameter and is always the caller's own,
 * resolved from the authenticated account by {@code TransactionService}. It is
 * never a value the client supplies.</p>
 */
@Repository
public interface TransactionRepository extends JpaRepository<Transaction, Long> {

    /**
     * One loan, but only if it belongs to this library.
     *
     * <p>Scoping the lookup rather than loading the row and checking afterwards
     * is what keeps the two failure cases identical, exactly as
     * {@link BookRepository#findByIdAndLibraryId} does: a loan in another
     * library and a loan that never existed both return empty, so a caller
     * cannot tell them apart - and the row is never read at all, which means a
     * refused return cannot act on data it was refused.</p>
     *
     * @param transactionId the loan wanted
     * @param libraryId     the caller's library
     * @return the loan, or empty if it is missing or belongs elsewhere
     */
    Optional<Transaction> findByIdAndLibraryId(Long transactionId, Long libraryId);

    /**
     * One library's loans recorded against one book.
     *
     * <p>Reads as {@code findBy} + {@code BookId} + {@code AndLibraryId}, both
     * of which are foreign keys already on the transactions row, so no join is
     * needed. A book id belonging to another library matches nothing and gives
     * the same empty list as a book nobody has ever borrowed; the two answers
     * are meant to be indistinguishable.</p>
     *
     * @param bookId    the book whose history is wanted
     * @param libraryId the caller's library
     * @return that library's loans against that book, oldest first
     */
    List<Transaction> findByBookIdAndLibraryId(Long bookId, Long libraryId);

    /**
     * One library's loans belonging to one user, resolved the same way via
     * {@code user_id}.
     *
     * <p>A user id from another library matches nothing, so a staff member
     * cannot read a neighbouring library's borrowing history by walking ids.</p>
     *
     * @param userId    the account whose history is wanted
     * @param libraryId the caller's library
     * @return that library's loans for that account
     */
    List<Transaction> findByUserIdAndLibraryId(Long userId, Long libraryId);

    /**
     * One library's loans in one state.
     *
     * <p>The scoping matters most here. Unscoped, this was the widest hole in
     * the API: a single request for ISSUED returned every open loan in every
     * library at once. Taking the {@link TransactionStatus} enum rather than a
     * String also keeps a typo a compile error instead of a query that quietly
     * returns nothing.</p>
     *
     * @param status    the state to match
     * @param libraryId the caller's library
     * @return that library's loans in that state
     */
    List<Transaction> findByStatusAndLibraryId(TransactionStatus status, Long libraryId);

    /**
     * Reports whether this library has ever recorded a loan against this book.
     *
     * <p>Used before deleting a book. Reading it as Spring Data does:
     * {@code exists} + {@code ByBookId} + {@code AndLibraryId}, both of which
     * are foreign keys already on the transactions row, giving
     * {@code SELECT count(*) FROM transactions WHERE book_id = ? AND library_id = ?}
     * with no join - and the database can stop at the first match.</p>
     *
     * <p>{@code boolean} rather than a List because the caller only needs to
     * know <i>whether</i> history exists, never what it says. That also keeps
     * this method outside the disclosure question the scoped finders answer: it
     * returns one bit about a book the caller has already been shown to own,
     * never a row.</p>
     *
     * <p>It carries the library anyway, for two reasons. It keeps every query
     * on this interface scoped, so the rule needs no exceptions to remember;
     * and it is equivalent to the unscoped question in any case, because a
     * transaction always belongs to the same library as its book - the issue
     * path sets both from the caller's own account, so the two cannot diverge.
     * Should that ever fail to hold, the foreign key still refuses the delete,
     * so this check can only ever be more cautious than the database.</p>
     *
     * @param bookId    the book about to be deleted
     * @param libraryId the caller's library
     * @return true if any transaction, open or returned, references this book
     */
    boolean existsByBookIdAndLibraryId(Long bookId, Long libraryId);
}
