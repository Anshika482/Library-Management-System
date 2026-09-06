package com.library.lms.dto;

import java.time.LocalDate;

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
 * null for a freshly issued book. Null is meaningful here - it says the book is
 * still out and nothing is owed - and keeping the fields present means the same
 * response shape describes a loan at every stage of its life.</p>
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

    /** Null unless a fine has been assessed. */
    private Double fineAmount;

    private TransactionStatus status;
}
