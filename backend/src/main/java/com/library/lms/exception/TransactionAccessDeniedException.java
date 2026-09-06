package com.library.lms.exception;

/**
 * Thrown when an authenticated caller asks for transaction data that is not
 * theirs to see.
 *
 * <p>Distinct from the not-found exceptions around it, and deliberately so.
 * Answering "no such user" to a member probing other people's history would
 * confirm which accounts exist, one id at a time. This exception carries a
 * single fixed message that says only that access was refused: the same answer
 * whether the requested account exists, is empty, or was never there.</p>
 *
 * <p>It takes no arguments for the same reason. There is no id, username or
 * reason to pass in, because none of it may reach the response.</p>
 */
public class TransactionAccessDeniedException extends RuntimeException {

    public TransactionAccessDeniedException() {
        super("Access denied");
    }
}
