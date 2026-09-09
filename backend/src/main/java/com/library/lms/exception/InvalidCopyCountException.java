package com.library.lms.exception;

/**
 * Thrown when a requested copy count would contradict what is already on loan.
 *
 * <p>A library holds {@code totalCopies} of a title, of which
 * {@code availableCopies} are on the shelf; the difference is what readers
 * currently have out. Lowering the total below that difference would claim
 * copies were never lent, leaving the catalogue disagreeing with the loan
 * records - and no arithmetic afterwards could say which was right.</p>
 *
 * <p>A caller error rather than a server fault, so the handler answers 400. The
 * message names only counts, never a row, a query or an internal detail.</p>
 */
public class InvalidCopyCountException extends RuntimeException {

    public InvalidCopyCountException(String message) {
        super(message);
    }
}
