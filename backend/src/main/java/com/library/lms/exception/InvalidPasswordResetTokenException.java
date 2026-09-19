package com.library.lms.exception;

/**
 * Raised when a password reset token cannot be redeemed.
 *
 * <p><b>One sentence for every reason</b>: a token that never existed, one
 * already used or superseded, one past its expiry, or one belonging to an
 * account that has since been disabled or locked. Telling them apart would let
 * a caller learn which tokens were once real. {@code GlobalExceptionHandler}
 * answers it with 400.</p>
 */
public class InvalidPasswordResetTokenException extends RuntimeException {

    private static final String MESSAGE = "Invalid or expired password reset token.";

    public InvalidPasswordResetTokenException() {
        super(MESSAGE);
    }
}
