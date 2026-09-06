package com.library.lms.exception;

/**
 * Thrown when a book cannot be issued because no copies are on the shelf.
 *
 * <p>This is not a missing book and not a malformed request - the book exists
 * and the request is perfectly valid. Every copy simply happens to be out on
 * loan right now, which is a conflict with the current state of the data rather
 * than an error by the caller. The same request will succeed once a copy comes
 * back.</p>
 *
 * <p>Deliberately a dedicated type rather than a bare {@link RuntimeException}:
 * a caller can catch exactly this case, and the eventual HTTP mapping can give
 * it its own status. That mapping is not written yet - there is no controller -
 * so no handler has been added to GlobalExceptionHandler.</p>
 */
public class BookNotAvailableException extends RuntimeException {

    /**
     * @param id    the id of the book with no copies left
     * @param title its title, so the message reads usefully without a lookup
     */
    public BookNotAvailableException(Long id, String title) {
        super("No copies available to issue for book: " + title + " (id " + id + ")");
    }
}
