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

    /**
     * For a lookup by login name.
     *
     * <p>Reached when a token is valid but the account behind it no longer
     * exists. The username is safe to repeat here: it came from the
     * authenticated principal, so the only person who can see this message is
     * the one who already supplied it. Nothing else about the account is
     * included.</p>
     *
     * @param username the login name that matched no account
     */
    public UserNotFoundException(String username) {
        super("User not found with username: " + username);
    }
}
