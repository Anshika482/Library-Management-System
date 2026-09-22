package com.library.lms.exception;

/**
 * Raised when someone other than an administrator asks to read the audit log.
 *
 * <p>The filter chain already restricts {@code /api/audit-events} to
 * administrators; this is the second lock, inside the service, so a loosened
 * rule does not open the log on its own. {@code GlobalExceptionHandler} answers
 * it with a fixed 403.</p>
 */
public class AuditAccessDeniedException extends RuntimeException {

    public AuditAccessDeniedException() {
        super("Access denied");
    }
}
