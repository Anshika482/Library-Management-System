package com.library.lms.controller;

import java.time.LocalDateTime;

import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.library.lms.exception.GlobalExceptionHandler.ErrorResponse;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Answers the servlet container's error dispatch in this API's JSON shape.
 *
 * <p>{@code GlobalExceptionHandler} catches anything thrown by a controller,
 * but not everything fails inside one. A failure in a servlet filter - the
 * security chain runs before any controller exists - is dispatched by the
 * container to {@code /error} instead, where Spring Boot's built-in controller
 * answers with a different body: an {@code error} field, the request
 * {@code path} echoed back, and an HTML page rather than JSON when the caller
 * asks for HTML. Declaring an {@link ErrorController} of our own replaces it,
 * so those failures come back looking like every other error here.</p>
 *
 * <p><b>Nothing about the failure is disclosed.</b> The container leaves the
 * exception, its message and the failing path in request attributes; none of
 * them is read. The reply carries the status code and one fixed sentence
 * chosen from it. The status itself is the only thing that varies, and it is
 * already visible in the status line.</p>
 *
 * <p><b>This route is not public.</b> The security chain covers the error
 * dispatch as well, and {@code anyRequest().authenticated()} applies here like
 * anywhere else, so an unauthenticated caller is answered by the 401 entry
 * point and never reaches this class. That is deliberate and unchanged.</p>
 */
@RestController
public class ApiErrorController implements ErrorController {

    /**
     * Builds the error response for whatever the container failed on.
     *
     * @param request the error dispatch, read only for the status code
     * @return the status the container recorded, with a fixed message
     */
    @RequestMapping("/error")
    public ResponseEntity<ErrorResponse> handleError(HttpServletRequest request) {
        HttpStatus status = statusOf(request);

        ErrorResponse errorResponse = new ErrorResponse(
                status.value(),
                messageFor(status),
                LocalDateTime.now());

        return ResponseEntity.status(status).body(errorResponse);
    }

    /**
     * The status the container recorded, falling back to 500.
     *
     * <p>A missing or unrecognised attribute becomes 500 rather than an
     * optimistic 200: if the code cannot be established, something did go
     * wrong, and saying so is the safe direction to be wrong in.</p>
     */
    private static HttpStatus statusOf(HttpServletRequest request) {
        Object attribute = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);

        if (attribute instanceof Integer code) {
            HttpStatus resolved = HttpStatus.resolve(code);
            if (resolved != null) {
                return resolved;
            }
        }

        return HttpStatus.INTERNAL_SERVER_ERROR;
    }

    /** One fixed sentence per family, matching what the rest of the API says. */
    private static String messageFor(HttpStatus status) {
        if (status == HttpStatus.NOT_FOUND) {
            return "Resource not found.";
        }
        if (status.is4xxClientError()) {
            return "The request could not be processed.";
        }

        return "An unexpected error occurred. Please try again later.";
    }
}
