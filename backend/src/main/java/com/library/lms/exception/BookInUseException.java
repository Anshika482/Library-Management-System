package com.library.lms.exception;

/**
 * Thrown when a book cannot be deleted because loans were recorded against it.
 *
 * <p>The mirror of {@link CategoryInUseException}, and refused for the same
 * reason: the request is well formed and the book exists, so this is a
 * <b>conflict with the current state of the data</b> rather than a validation
 * failure or a missing resource. GlobalExceptionHandler answers 409.</p>
 *
 * <p>The policy is deliberately broader than "has an open loan". <b>Any</b>
 * transaction blocks the delete, returned ones included, because those rows are
 * the audit trail of who held what and when. Deleting the book would leave that
 * history pointing at a title nobody can name any more - and a borrowing record
 * that cannot say what was borrowed is not a record. A book that is genuinely
 * finished with can be taken off the shelf by setting its copy counts to zero,
 * which keeps the history readable.</p>
 *
 * <p>Checking first rather than letting the foreign key reject the statement
 * matters too. The database would refuse it either way, but as a generic
 * integrity error that names a constraint and explains nothing; this says
 * plainly what happened and leaves every row untouched.</p>
 *
 * <p>The message names the book by id and title, so whoever hits it knows
 * which one they were trying to remove.</p>
 */
public class BookInUseException extends RuntimeException {

    /**
     * @param id    the id of the book that still has loan history
     * @param title the title of that book
     */
    public BookInUseException(Long id, String title) {
        super("Book cannot be deleted because transactions are recorded against it: "
                + title + " (id " + id + ")");
    }
}
