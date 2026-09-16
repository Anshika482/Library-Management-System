package com.library.lms.exception;

/**
 * Raised when an account cannot be created because its username or email is
 * already taken.
 *
 * <p><b>One message for both fields, on purpose.</b> {@code users.username} and
 * {@code users.email} are unique across the whole table rather than per
 * library, so a reply naming which of the two clashed would tell an
 * administrator something about an account that may belong to a different
 * library entirely. Saying only that one of them is taken leaks the least the
 * constraint allows.</p>
 *
 * <p>That any clash is visible at all is inherent to global uniqueness; making
 * it invisible would mean scoping those constraints per library, which is a
 * schema change rather than a message change.</p>
 */
public class DuplicateAccountException extends RuntimeException {

    private static final String MESSAGE = "An account with that username or email already exists.";

    public DuplicateAccountException() {
        super(MESSAGE);
    }
}
