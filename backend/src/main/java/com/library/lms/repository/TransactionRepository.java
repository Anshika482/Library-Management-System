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
}
