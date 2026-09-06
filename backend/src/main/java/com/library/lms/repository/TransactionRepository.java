package com.library.lms.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.library.lms.entity.Transaction;
import com.library.lms.entity.TransactionStatus;

/**
 * Data-access layer for {@link Transaction}.
 *
 * <p>Three derived queries, no @Query and no SQL - each answers one question the
 * borrowing feature will need: the history of a book, the history of a user, and
 * everything currently in a given state.</p>
 *
 * <p>Nothing calls these yet. The service and controller layers come in a later
 * step; this interface exists so the persistence side is complete first.</p>
 */
@Repository
public interface TransactionRepository extends JpaRepository<Transaction, Long> {

    /**
     * Every transaction recorded against one book.
     *
     * <p>Spring Data reads {@code ByBook} (the association on Transaction) plus
     * {@code Id} (the key on Book) and matches on the {@code book_id} column
     * directly, so no join is needed - the foreign key is already on the row.</p>
     *
     * <p>Returns the book's whole borrowing history, returned copies included,
     * not just the times it is out now.</p>
     */
    List<Transaction> findByBookId(Long bookId);

    /** Every transaction belonging to one user, resolved the same way via {@code user_id}. */
    List<Transaction> findByUserId(Long userId);

    /**
     * Every transaction in one state.
     *
     * <p>Takes the {@link TransactionStatus} enum rather than a String, so a
     * typo is a compile error instead of a query that quietly returns nothing.</p>
     */
    List<Transaction> findByStatus(TransactionStatus status);
}
