package com.library.lms.dto;

import java.time.LocalDate;

import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * What a client sends to borrow a book.
 *
 * <p>Two fields, and the list is as notable for what it leaves out. There is
 * no {@code issueDate}, {@code status}, {@code returnDate} or {@code fineAmount}
 * here: those are decided by the service, not the caller. Accepting them would
 * let a client backdate a loan or declare a book already returned.</p>
 *
 * <p><b>There is no {@code userId} either, and that absence is the point.</b>
 * The borrower used to be named in this body, which meant any authenticated
 * caller could issue a book to somebody else simply by typing a different
 * number. Who is borrowing is now taken from the authenticated principal, a
 * value the server established itself and the client cannot influence. A field
 * that must not be trusted is better removed than validated.</p>
 *
 * <p>Input only. It never travels back out, and it holds no entity - just the
 * book id the service will resolve for itself.</p>
 */
@Getter
@Setter
@ToString
@NoArgsConstructor
@AllArgsConstructor
public class IssueBookRequest {

    /**
     * Which book to issue.
     *
     * <p>{@code @Positive} rejects zero and negatives before any lookup happens.
     * No such id can exist - the column is AUTO_INCREMENT starting at 1 - so
     * catching it here turns a guaranteed "not found" into a clearer complaint
     * about the input itself.</p>
     */
    @NotNull(message = "Book id is required")
    @Positive(message = "Book id must be a positive number")
    private Long bookId;

    /**
     * When the book must come back.
     *
     * <p>{@code @FutureOrPresent} refuses a date already in the past: a loan
     * cannot be due back before it was lent out, and accepting one would create
     * a record that is overdue the moment it exists. Today is allowed, since a
     * same-day loan is legitimate.</p>
     *
     * <p>{@code @NotNull} is kept as well. The two are not redundant -
     * {@code @FutureOrPresent} passes a null value, so without {@code @NotNull}
     * a missing date would reach the service and fail against the NOT NULL
     * column instead of being reported as a validation error.</p>
     *
     * <p>No upper bound is imposed. How far ahead a due date may sit is a
     * lending-policy question rather than a technical one, and a date beyond
     * what the database column can store is caught downstream and reported
     * cleanly rather than being second-guessed here.</p>
     */
    @NotNull(message = "Due date is required")
    @FutureOrPresent(message = "Due date must be today or a future date")
    private LocalDate dueDate;
}
