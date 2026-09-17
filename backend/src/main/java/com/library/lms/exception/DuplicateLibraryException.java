package com.library.lms.exception;

/**
 * Raised when a library is created under a name another library already has.
 *
 * <p>Library names are unique across the whole system - a library is the
 * outermost scope there is, so there is nothing wider for a name to be unique
 * within. Two tenants sharing a name would leave nobody able to tell them
 * apart.</p>
 *
 * <p><b>A fixed message.</b> Unlike the category equivalent it does not repeat
 * the name back. The caller already knows what they sent, and a response is no
 * place to reflect a client's input. That some library holds the name is the
 * one thing it does say, and a unique name cannot avoid saying that.</p>
 */
public class DuplicateLibraryException extends RuntimeException {

    private static final String MESSAGE = "A library with that name already exists.";

    public DuplicateLibraryException() {
        super(MESSAGE);
    }
}
