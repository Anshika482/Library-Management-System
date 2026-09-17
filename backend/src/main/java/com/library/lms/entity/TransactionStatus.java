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
 * <p>ISSUED and RETURNED are what the application stores. OVERDUE is decided
 * rather than stored: an open loan is reported OVERDUE from the day after its
 * due date, because a status written once would be wrong from the next
 * midnight until something rewrote it. A row that is stored as OVERDUE is
 * still treated as an open loan.</p>
 */
public enum TransactionStatus {

    /** The book is out with a borrower and not yet back. */
    ISSUED,

    /** The book has been brought back; {@code returnDate} will be set. */
    RETURNED,

    /** Still out, and past its due date - reported from the dates, not written. */
    OVERDUE
}
