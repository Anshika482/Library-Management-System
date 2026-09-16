package com.library.lms.exception;

/**
 * Raised when someone changing their own password does not know the current
 * one.
 *
 * <p>Nothing is disclosed by saying so plainly: the caller is already
 * authenticated as this very account, so the only thing the message confirms is
 * something they already proved. This is deliberately not the generic login
 * failure - that message would be actively misleading here, since the account
 * and the session are both perfectly valid and only the typed password was
 * wrong.</p>
 */
public class InvalidCurrentPasswordException extends RuntimeException {

    private static final String MESSAGE = "Current password is incorrect.";

    public InvalidCurrentPasswordException() {
        super(MESSAGE);
    }
}
