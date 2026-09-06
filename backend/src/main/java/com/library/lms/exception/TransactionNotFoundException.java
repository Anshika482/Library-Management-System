package com.library.lms.exception;

/**
 * Thrown when a transaction is referenced by id but no such record exists.
 *
 * <p>A sibling of {@link BookNotFoundException}, {@link UserNotFoundException}
 * and {@link CategoryNotFoundException} - same unchecked base class, same shape.
 * It exists because returning a book starts by looking one up, and "there is no
 * such loan" has to be reported as itself rather than borrowed from another
 * resource's exception or thrown as a bare RuntimeException.</p>
 *
 * <p>Distinct from {@link ReturnBookNotAllowedException}: this means the record
 * is absent, that one means the record is present but in the wrong state.</p>
 */
public class TransactionNotFoundException extends RuntimeException {

    /**
     * @param id the transaction id that was looked up and not found
     */
    public TransactionNotFoundException(Long id) {
        super("Transaction not found with id: " + id);
    }
}
