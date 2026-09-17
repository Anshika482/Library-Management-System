package com.library.lms.exception;

/**
 * Raised when an administrator asks to disable or lock their own account.
 *
 * <p>The status endpoint is the only way back from a disabled or locked
 * account, and it needs an administrator who can still log in. One who shuts
 * themselves out has removed that administrator - and in a library with only
 * one, every administrator it has - with nothing in the API able to undo it.</p>
 *
 * <p>One fixed sentence for both switches: the fix is the same either way, and
 * the caller already knows which one they sent. GlobalExceptionHandler answers
 * it with 400 BAD REQUEST.</p>
 */
public class SelfLockoutException extends RuntimeException {

    private static final String MESSAGE = "You cannot disable or lock your own account.";

    public SelfLockoutException() {
        super(MESSAGE);
    }
}
