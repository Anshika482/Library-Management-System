package com.library.lms.exception;

/**
 * Thrown when a user is referenced by id but no such account exists.
 *
 * <p>A sibling of {@link BookNotFoundException} and
 * {@link CategoryNotFoundException} - same unchecked base class, same shape,
 * and it exists for the same reason: issuing a book needs a real borrower, and
 * "there is no such user" has to be reported as itself rather than borrowed
 * from another resource's exception or thrown as a bare RuntimeException.</p>
 */
public class UserNotFoundException extends RuntimeException {

    /**
     * @param id the user id that was looked up and not found
     */
    public UserNotFoundException(Long id) {
        super("User not found with id: " + id);
    }
}
