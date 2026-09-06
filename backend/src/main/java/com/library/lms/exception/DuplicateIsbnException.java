package com.library.lms.exception;

/**
 * Thrown when a book is saved with an ISBN that another book already holds.
 *
 * <p>An ISBN identifies a title worldwide, so the {@code books} table declares
 * it {@code unique}. Without this exception the database would still refuse the
 * duplicate, but the failure would arrive as a Hibernate
 * {@code DataIntegrityViolationException} and surface to the client as a 500
 * carrying an SQL constraint name - noise that exposes our schema and tells the
 * caller nothing useful.</p>
 *
 * <p>Checking first and throwing this instead keeps the database untouched and
 * lets the API answer with a plain sentence naming the offending ISBN.</p>
 *
 * <p>Like {@link BookNotFoundException} it extends {@link RuntimeException}, so
 * no caller is forced into a try/catch; GlobalExceptionHandler turns it into a
 * 400 response.</p>
 */
public class DuplicateIsbnException extends RuntimeException {

    /**
     * @param isbn the ISBN that is already taken
     */
    public DuplicateIsbnException(String isbn) {
        super("A book with ISBN already exists: " + isbn);
    }
}
