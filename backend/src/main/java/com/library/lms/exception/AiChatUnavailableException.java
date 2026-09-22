package com.library.lms.exception;

/**
 * Raised when the assistant's provider cannot answer: it timed out, refused the
 * request, rate-limited it, failed, or sent back something with no answer in
 * it.
 *
 * <p>One exception for all of those, and one fixed message. A provider's own
 * error text quotes the request back and describes the account behind the key,
 * so none of it reaches a caller or a log line - only the exception's type
 * does. {@code GlobalExceptionHandler} answers with 503: nothing is wrong with
 * the request, and asking again later may work.</p>
 */
public class AiChatUnavailableException extends RuntimeException {

    public AiChatUnavailableException() {
        super("The assistant is unavailable right now.");
    }
}
