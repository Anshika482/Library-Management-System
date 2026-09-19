package com.library.lms.exception;

/**
 * Raised when a caller asks the user directory for accounts their role may not
 * see - a librarian filtering for administrators or librarians.
 *
 * <p>Librarians may list and view members of their own library and nothing
 * else. The filter chain lets them reach the directory at all; this is the
 * finer rule inside it. {@code GlobalExceptionHandler} answers it with a fixed
 * 403, and the message names nothing about the accounts that were asked
 * for.</p>
 *
 * <p>Not Spring Security's {@code AccessDeniedException}: thrown from a
 * service, that type would be caught by the catch-all handler and answered with
 * a 500.</p>
 */
public class UserDirectoryAccessDeniedException extends RuntimeException {

    public UserDirectoryAccessDeniedException() {
        super("Access denied");
    }
}
