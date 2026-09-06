package com.library.lms.entity;

/**
 * Where a borrowed book has got to.
 *
 * <p>The three names are fixed by the existing {@code transactions.status}
 * column, a MySQL {@code ENUM('ISSUED','RETURNED','OVERDUE')}. They must match
 * those strings exactly, because {@link Transaction} stores the constant's
 * <i>name</i> rather than its position - see the {@code @Enumerated(STRING)}
 * there.</p>
 *
 * <p>Modelling the lifecycle as an explicit state, rather than working it out
 * from the dates each time, means a query can ask "what is overdue?" with a
 * simple equality check instead of date arithmetic on every row.</p>
 */
public enum TransactionStatus {

    /** The book is out with a borrower and not yet back. */
    ISSUED,

    /** The book has been brought back; {@code returnDate} will be set. */
    RETURNED,

    /** Still out, and past its due date. */
    OVERDUE
}
