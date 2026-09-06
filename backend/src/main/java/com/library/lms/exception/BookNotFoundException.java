package com.library.lms.exception;

/**
 * Thrown when a book is requested by id but no such row exists.
 *
 * <p>It extends {@link RuntimeException} (an <i>unchecked</i> exception), so
 * calling code is not forced to wrap every service call in try/catch. That is
 * the normal choice for this kind of error: a missing book is not something the
 * service layer can recover from, it is something the caller must be told
 * about.</p>
 *
 * <p>Right now nothing catches this, so it will surface as a generic HTTP 500.
 * Translating it into a proper 404 belongs to the web layer and is deliberately
 * left for a later step.</p>
 */
public class BookNotFoundException extends RuntimeException {

    /**
     * @param id the book id that was looked up and not found
     */
    public BookNotFoundException(Long id) {
        super("Book not found with id: " + id);
    }
}
