package com.library.lms.exception;

/**
 * Thrown when the page or size asked for makes no sense.
 *
 * <p>Covers a negative page, a size below 1, and a size above the allowed
 * maximum. The last of those is not merely tidiness: without an upper bound a
 * client could ask for a single page of every row in the table, which defeats
 * the point of paginating and hands anyone a cheap way to exhaust the server's
 * memory.</p>
 *
 * <p>The message says which value was wrong and what the limit is, so the
 * caller can correct the request without guessing. GlobalExceptionHandler maps
 * it to 400, the same as any other malformed request.</p>
 */
public class InvalidPaginationException extends RuntimeException {

    /**
     * @param message what was wrong with the page or size
     */
    public InvalidPaginationException(String message) {
        super(message);
    }
}
