package com.library.lms.exception;

/**
 * Raised when a refresh token cannot be exchanged.
 *
 * <p><b>One sentence for every reason</b>: a token that never existed, one
 * already used, one revoked at logout, one past its session's end, or one
 * belonging to an account that is disabled or locked. Telling them apart would
 * let a caller learn which tokens were once real, and whether an account has
 * been disabled. GlobalExceptionHandler answers it with 401.</p>
 *
 * <p>Not a Spring Security {@code AuthenticationException}, on purpose: that
 * type is answered with the login failure's own message, which would be wrong
 * here.</p>
 */
public class InvalidRefreshTokenException extends RuntimeException {

    private static final String MESSAGE = "Invalid or expired refresh token.";

    public InvalidRefreshTokenException() {
        super(MESSAGE);
    }
}
