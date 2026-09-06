package com.library.lms.exception;

/**
 * Thrown when the requested sort field or direction is not supported.
 *
 * <p>Separate from {@link InvalidPaginationException} on purpose. Both arrive on
 * the same request and both answer 400, but "you asked for page -1" and "you
 * asked to sort by a column that does not exist" are different mistakes, and
 * keeping them apart lets each carry a message that actually helps.</p>
 *
 * <p>This exception is the <b>safety boundary</b> for sorting. A sort field
 * arrives as free text from the client and would otherwise be handed straight
 * to Spring Data, which turns it into a property path in the generated query.
 * An unrecognised name has to be stopped here, with a plain sentence, rather
 * than allowed to fail deeper down as a JPQL or SQL error that leaks the
 * entity's internals.</p>
 */
public class InvalidSortException extends RuntimeException {

    /**
     * @param message what was wrong with the sort field or direction
     */
    public InvalidSortException(String message) {
        super(message);
    }
}
