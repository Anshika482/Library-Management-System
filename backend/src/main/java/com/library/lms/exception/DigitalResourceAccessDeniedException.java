package com.library.lms.exception;

/**
 * Raised when a member tries to create, change or remove a digital resource.
 *
 * <p>The filter chain already restricts those verbs to staff; this is the
 * second lock, inside the service, so a loosened rule does not by itself let a
 * member edit the catalogue. {@code GlobalExceptionHandler} answers it with a
 * fixed 403 that describes nothing about the resource.</p>
 */
public class DigitalResourceAccessDeniedException extends RuntimeException {

    public DigitalResourceAccessDeniedException() {
        super("Access denied");
    }
}
