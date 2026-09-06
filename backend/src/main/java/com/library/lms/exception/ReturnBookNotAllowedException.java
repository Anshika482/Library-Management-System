package com.library.lms.exception;

import com.library.lms.entity.TransactionStatus;

/**
 * Thrown when a transaction exists but cannot be returned.
 *
 * <p>The common case is a book that has already been given back: its
 * transaction is {@code RETURNED}, and accepting a second return would credit
 * the library with a copy it never lost. Silently succeeding would be the worst
 * outcome - the count would drift upward with every repeat call and no one
 * would know why.</p>
 *
 * <p>Deliberately distinct from the not-found exceptions. Here the transaction
 * <b>does</b> exist and the request is well formed; what fails is the state it
 * is in, which is why this maps to 409 rather than 404 or 400.</p>
 */
public class ReturnBookNotAllowedException extends RuntimeException {

    /**
     * The ordinary case: the transaction is not in a returnable state.
     *
     * @param transactionId the transaction that cannot be returned
     * @param status        the state it is actually in
     */
    public ReturnBookNotAllowedException(Long transactionId, TransactionStatus status) {
        super("Transaction cannot be returned because its status is " + status
                + " rather than " + TransactionStatus.ISSUED + " (id " + transactionId + ")");
    }

    /**
     * For the defensive checks - data that should be impossible but would
     * otherwise corrupt a copy count or throw an opaque NullPointerException.
     *
     * @param message what specifically prevents the return
     */
    public ReturnBookNotAllowedException(String message) {
        super(message);
    }
}
