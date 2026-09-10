package com.library.lms.exception;

import java.time.LocalDate;

/**
 * Thrown when a loan's due date cannot follow from its issue date.
 *
 * <p>A caller error rather than a server fault, so the handler answers 400 -
 * the same shape as {@link InvalidCopyCountException}, which refuses a copy
 * count that contradicts what is already on loan.</p>
 *
 * <p>{@code IssueBookRequest} already carries {@code @FutureOrPresent}, and that
 * annotation stays. It is not enough on its own for two reasons. It only runs
 * for callers that arrive through the validated HTTP boundary, so a scheduler,
 * an import or a second controller calling the service directly would bypass it
 * entirely. And it compares against the moment of <i>validation</i>, while the
 * issue date is stamped at the moment of <i>execution</i>: a request validated
 * at 23:59:59 and executed a second later is issued on a day its due date now
 * precedes. Checking here compares the two dates that actually get stored.</p>
 *
 * <p>The message names only the two dates. Neither is sensitive - the caller
 * supplied one and the server stamped the other - and seeing both is what makes
 * the refusal actionable.</p>
 */
public class InvalidDueDateException extends RuntimeException {

    /**
     * @param dueDate   the date the caller asked for
     * @param issueDate the date the server stamped on the loan
     */
    public InvalidDueDateException(LocalDate dueDate, LocalDate issueDate) {
        super("Due date cannot be before the issue date: due " + dueDate
                + " but issued " + issueDate);
    }

    /** A due date that never arrived at all. */
    public InvalidDueDateException() {
        super("Due date is required");
    }
}
