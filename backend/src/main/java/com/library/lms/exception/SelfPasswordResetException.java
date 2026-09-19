package com.library.lms.exception;

/**
 * Raised when an administrator tries to reset their own password through the
 * staff reset.
 *
 * <p>The staff reset needs no current password - the caller's authority stands
 * in for it. Allowed on one's own account, that would let anyone holding a
 * stolen administrator token set a new password and keep the account after the
 * token expired. Changing your own password goes through
 * {@code POST /api/auth/password}, which asks for the current one.
 * {@code GlobalExceptionHandler} answers this with 400.</p>
 */
public class SelfPasswordResetException extends RuntimeException {

    private static final String MESSAGE =
            "You cannot reset your own password here. Use POST /api/auth/password with your current password.";

    public SelfPasswordResetException() {
        super(MESSAGE);
    }
}
