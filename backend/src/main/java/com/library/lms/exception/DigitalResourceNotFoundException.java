package com.library.lms.exception;

/**
 * Raised when a library has no digital resource with the given id - including
 * when the resource exists in another library, or exists here but is disabled
 * and the caller is a member.
 *
 * <p>All three answer 404, deliberately. A separate 403 for "it exists but not
 * for you" would tell a caller which ids are real in libraries they cannot
 * see, and which resources a library has chosen to hide.</p>
 */
public class DigitalResourceNotFoundException extends RuntimeException {

    public DigitalResourceNotFoundException(Long id) {
        super("Digital resource not found with id: " + id);
    }
}
