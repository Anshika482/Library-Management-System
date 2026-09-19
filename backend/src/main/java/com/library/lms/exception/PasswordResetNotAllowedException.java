package com.library.lms.exception;

/**
 * Raised when a caller may not reset the password of the account they named: a
 * librarian naming a member of staff, or a member naming anyone.
 *
 * <p>The filter chain lets administrators and librarians reach the reset; this
 * is the finer rule inside it, and the second lock against members.
 * {@code GlobalExceptionHandler} answers it with a fixed 403 that names nothing
 * about the target account.</p>
 *
 * <p>Not Spring Security's {@code AccessDeniedException}: thrown from a
 * service, that type would be caught by the catch-all handler and answered with
 * a 500.</p>
 */
public class PasswordResetNotAllowedException extends RuntimeException {

    public PasswordResetNotAllowedException() {
        super("Access denied");
    }
}
