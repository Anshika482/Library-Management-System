package com.library.lms.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;

import com.library.lms.entity.FinePaymentStatus;
import com.library.lms.entity.TransactionStatus;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * What the API sends back describing one borrowing.
 *
 * <p>The book and the user appear as <b>ids only</b>. That is the point of the
 * DTO: returning the Transaction entity would drag its {@code Book} and
 * {@code User} along with it, and the User carries a BCrypt password hash, an
 * email and a role. None of that belongs in a response about a loan, and it
 * would be published by accident rather than by decision. A client that wants
 * the book's details can ask {@code /api/books/{id}}.</p>
 *
 * <p>{@code returnDate} and {@code fineAmount} are included even though they are
 * null for a freshly issued book, so the same response shape describes a loan
 * at every stage of its life. {@code status} and {@code fineAmount} describe the
 * loan as it stands on the day of the request: an open loan past its due date
 * is OVERDUE with the fine it has run up so far, and a returned loan carries
 * the fine fixed when it came back.</p>
 *
 * <p>Output only, and it holds no entity. {@link TransactionStatus} is an enum
 * rather than an entity, so exposing it publishes a fixed vocabulary
 * ("ISSUED", "RETURNED", "OVERDUE") and no database structure.</p>
 */
@Getter
@Setter
@ToString
@NoArgsConstructor
@AllArgsConstructor
public class TransactionResponse {

    /** Assigned by the database when the loan is recorded. */
    private Long id;

    /** The borrowed book, by id - never the Book object. */
    private Long bookId;

    /** The borrower, by id - never the User object, and never their details. */
    private Long userId;

    private LocalDate issueDate;

    private LocalDate dueDate;

    /** Null while the book is still out. */
    private LocalDate returnDate;

    /**
     * What is owed for lateness: the amount so far for an open overdue loan, the
     * amount fixed on return for a returned one - zero if it came back on time.
     * Null for an open loan that is not overdue, and for a loan returned before
     * fines were calculated.
     */
    private Double fineAmount;

    private TransactionStatus status;

    /**
     * Whether the fine has been settled. UNPAID while something is owed - on an
     * open loan it is still growing and cannot be paid until the book is back -
     * PAID once staff have recorded a payment, and NOT_REQUIRED when the fine
     * came to nothing. Null when there is no fine: an open loan not yet overdue,
     * or a loan returned before fines were calculated.
     */
    private FinePaymentStatus finePaymentStatus;

    /** When staff recorded the payment. Null until they have. */
    private LocalDateTime finePaidAt;
}
