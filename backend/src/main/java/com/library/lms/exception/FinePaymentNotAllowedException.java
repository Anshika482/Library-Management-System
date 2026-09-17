package com.library.lms.exception;

/**
 * Raised when a fine payment cannot be recorded for a loan.
 *
 * <p>Three reasons, each with its own fixed sentence and nothing more - no id,
 * no amount, no name. The caller already knows which loan they asked about, and
 * a fixed message cannot leak anything a later edit adds to the loan.</p>
 *
 * <p>Created only through the factories below, so no other message can be
 * used. GlobalExceptionHandler answers every one with 409 CONFLICT: the request
 * is well formed and the loan exists, but its state will not allow a payment.</p>
 */
public class FinePaymentNotAllowedException extends RuntimeException {

    private FinePaymentNotAllowedException(String message) {
        super(message);
    }

    /** The book is still out, so its fine is still growing and cannot be settled. */
    public static FinePaymentNotAllowedException bookStillOut() {
        return new FinePaymentNotAllowedException("A fine can be paid only after the book has been returned.");
    }

    /** A payment has already been recorded; a second would record money twice. */
    public static FinePaymentNotAllowedException alreadyPaid() {
        return new FinePaymentNotAllowedException("This fine has already been paid.");
    }

    /** The loan owes nothing: returned on time, or from before fines were calculated. */
    public static FinePaymentNotAllowedException nothingOwed() {
        return new FinePaymentNotAllowedException("There is no fine to pay on this loan.");
    }
}
