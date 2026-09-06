package com.library.lms.exception;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.stream.Collectors;

import org.springframework.context.MessageSourceResolvable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Turns exceptions thrown anywhere in the application into tidy JSON responses.
 *
 * <p>{@code @RestControllerAdvice} is the key annotation. "Advice" means this
 * class sits <i>around</i> every controller: when a controller method throws
 * something, Spring looks here for a matching {@code @ExceptionHandler} before
 * falling back to its own default error page. The "Rest" prefix adds
 * {@code @ResponseBody}, so whatever a handler returns becomes the JSON body.</p>
 *
 * <p>The benefit is that {@link com.library.lms.controller.BookController} stays
 * free of try/catch blocks. The controller describes the happy path only, the
 * service raises an exception when something is wrong, and the translation into
 * an HTTP status code happens once, here.</p>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * The shape of the JSON sent back when something goes wrong.
     *
     * <p>A {@code record} is a short way of declaring a class whose only job is
     * to carry a few values: Java writes the constructor and the accessors for
     * us, and Jackson uses those accessors to build the JSON. The three
     * components appear in the response in the order written here.</p>
     *
     * <p>It is nested inside the handler on purpose - it is only used here, and
     * keeping it local avoids growing an error-handling framework we do not
     * need yet.</p>
     *
     * @param status    the HTTP status code, e.g. 404
     * @param message   what went wrong, taken from the exception
     * @param timestamp when the error happened
     */
    public record ErrorResponse(int status, String message, LocalDateTime timestamp) {
    }

    /**
     * Handles a lookup for a book that does not exist.
     *
     * <p>{@code @ExceptionHandler(BookNotFoundException.class)} tells Spring to
     * call this method whenever that exception escapes a controller - which
     * happens on GET, PUT and DELETE for an unknown id.</p>
     *
     * <p>The reply is <b>404 NOT FOUND</b> rather than the default 500. That
     * distinction matters: 500 means "the server is broken", while 404 means
     * "your request was fine, that book simply is not here". Only the second is
     * true, and a client can act on it sensibly.</p>
     *
     * <p>The message comes straight from {@code exception.getMessage()}, which
     * BookNotFoundException built as "Book not found with id: 42".</p>
     */
    @ExceptionHandler(BookNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleBookNotFound(BookNotFoundException exception) {
        ErrorResponse errorResponse = new ErrorResponse(
                HttpStatus.NOT_FOUND.value(),
                exception.getMessage(),
                LocalDateTime.now());

        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(errorResponse);
    }

    /**
     * Handles a request body that failed Bean Validation.
     *
     * <p>When a controller parameter is marked {@code @Valid} and the object
     * breaks one of its rules, Spring never calls the controller method at all -
     * it throws {@code MethodArgumentNotValidException} instead. Catching it
     * here replaces Spring's default error page with the same tidy JSON shape
     * the rest of the API uses.</p>
     *
     * <p>The reply is <b>400 BAD REQUEST</b>: the server is fine, the data sent
     * was not. That is a different situation from a 404, where the request was
     * well formed but the book did not exist.</p>
     *
     * <p>A single request can break several rules at once, so all of them are
     * collected and joined with "; " into one readable sentence, for example
     * "Title is required; ISBN is required". Each piece comes from
     * {@code getDefaultMessage()}, which returns the text written on the
     * annotation in BookRequest - so the messages live in exactly one place and
     * are never repeated here.</p>
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidationErrors(MethodArgumentNotValidException exception) {
        String message = exception.getBindingResult()
                .getFieldErrors()
                .stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("; "));

        ErrorResponse errorResponse = new ErrorResponse(
                HttpStatus.BAD_REQUEST.value(),
                message,
                LocalDateTime.now());

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorResponse);
    }

    /**
     * Handles an attempt to save a book under an ISBN that is already taken.
     *
     * <p>The reply is <b>400 BAD REQUEST</b> and reuses the same
     * {@link ErrorResponse} shape as the other handlers, so every error this API
     * produces looks the same to a client.</p>
     *
     * <p>400 is the right choice because the caller supplied data the server
     * cannot accept. The service raises this <i>before</i> touching the
     * database, so no failed INSERT happens and no SQL detail or stack trace
     * ever reaches the response - only the sentence the exception carries,
     * which names the offending ISBN.</p>
     */
    @ExceptionHandler(DuplicateIsbnException.class)
    public ResponseEntity<ErrorResponse> handleDuplicateIsbn(DuplicateIsbnException exception) {
        ErrorResponse errorResponse = new ErrorResponse(
                HttpStatus.BAD_REQUEST.value(),
                exception.getMessage(),
                LocalDateTime.now());

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorResponse);
    }

    /**
     * Handles a request that left out a required query parameter.
     *
     * <p>{@code GET /api/books/search} declares {@code @RequestParam String
     * keyword}, so calling it without {@code ?keyword=...} makes Spring throw
     * {@code MissingServletRequestParameterException} before the controller
     * runs. Left unhandled it produced Spring's own error body, which carries a
     * {@code trace} field containing the full Java stack - internal detail a
     * client should never see.</p>
     *
     * <p>Catching it here replaces that with the same {@link ErrorResponse} the
     * rest of the API uses. Since the record has only three components, a
     * {@code trace} field cannot appear in the output at all.</p>
     *
     * <p>The message is built from {@code getParameterName()} rather than
     * {@code getMessage()}: the latter appends wording about Java method
     * parameter types, which describes our code rather than the caller's
     * mistake. Naming the parameter alone tells them exactly what to fix.</p>
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorResponse> handleMissingRequestParameter(
            MissingServletRequestParameterException exception) {
        String message = "Required request parameter is missing: " + exception.getParameterName();

        ErrorResponse errorResponse = new ErrorResponse(
                HttpStatus.BAD_REQUEST.value(),
                message,
                LocalDateTime.now());

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorResponse);
    }

    /**
     * Handles a book that refers to a category id which does not exist.
     *
     * <p>Answers <b>404 NOT FOUND</b> for the same reason as a missing book:
     * the request was well formed, the thing it names is simply not there. It
     * reuses the shared {@link ErrorResponse}, so it looks like every other
     * error this API returns.</p>
     */
    @ExceptionHandler(CategoryNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleCategoryNotFound(CategoryNotFoundException exception) {
        ErrorResponse errorResponse = new ErrorResponse(
                HttpStatus.NOT_FOUND.value(),
                exception.getMessage(),
                LocalDateTime.now());

        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(errorResponse);
    }

    /**
     * Handles an attempt to create a category whose name is already taken.
     *
     * <p><b>400 BAD REQUEST</b>, matching {@link DuplicateIsbnException}: the
     * caller supplied data the server cannot accept. The service raises it
     * before saving, so the existing category is untouched and no SQL
     * constraint error reaches the response - only the sentence naming the
     * duplicate.</p>
     */
    @ExceptionHandler(DuplicateCategoryException.class)
    public ResponseEntity<ErrorResponse> handleDuplicateCategory(DuplicateCategoryException exception) {
        ErrorResponse errorResponse = new ErrorResponse(
                HttpStatus.BAD_REQUEST.value(),
                exception.getMessage(),
                LocalDateTime.now());

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorResponse);
    }

    /**
     * Handles an attempt to delete a category that books still reference.
     *
     * <p>Answers <b>409 CONFLICT</b>, and the choice of code is deliberate. It
     * is not a 400 - the request is perfectly well formed. It is not a 404 -
     * the category exists. 409 is the code for "this clashes with the current
     * state of the data", which is exactly the situation: the delete would be
     * valid once those books point somewhere else.</p>
     *
     * <p>Uses the shared {@link ErrorResponse}, so it reads like every other
     * error this API produces, and the message names the category rather than
     * exposing a database constraint.</p>
     */
    @ExceptionHandler(CategoryInUseException.class)
    public ResponseEntity<ErrorResponse> handleCategoryInUse(CategoryInUseException exception) {
        ErrorResponse errorResponse = new ErrorResponse(
                HttpStatus.CONFLICT.value(),
                exception.getMessage(),
                LocalDateTime.now());

        return ResponseEntity.status(HttpStatus.CONFLICT).body(errorResponse);
    }

    /**
     * Handles a page or size the API cannot honour.
     *
     * <p><b>400 BAD REQUEST</b>: the caller asked for something impossible - a
     * negative page, a size below one, or a size beyond the allowed maximum.
     * Nothing is wrong on the server, so 400 rather than 500.</p>
     *
     * <p>Uses the shared {@link ErrorResponse}, and the message carries the
     * limit that was broken so the caller can fix the request directly.</p>
     */
    @ExceptionHandler(InvalidPaginationException.class)
    public ResponseEntity<ErrorResponse> handleInvalidPagination(InvalidPaginationException exception) {
        ErrorResponse errorResponse = new ErrorResponse(
                HttpStatus.BAD_REQUEST.value(),
                exception.getMessage(),
                LocalDateTime.now());

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorResponse);
    }

    /**
     * Handles a sort field or direction the API does not support.
     *
     * <p><b>400 BAD REQUEST</b>: the caller named something that is not a
     * sortable field, or a direction other than asc/desc. Nothing failed on the
     * server, so 400 rather than 500.</p>
     *
     * <p>Catching it here is what keeps an unrecognised sort field from ever
     * becoming a Hibernate PropertyReferenceException. The reply carries only
     * the list of names the API accepts - no entity fields, no SQL, no
     * package details.</p>
     */
    @ExceptionHandler(InvalidSortException.class)
    public ResponseEntity<ErrorResponse> handleInvalidSort(InvalidSortException exception) {
        ErrorResponse errorResponse = new ErrorResponse(
                HttpStatus.BAD_REQUEST.value(),
                exception.getMessage(),
                LocalDateTime.now());

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorResponse);
    }

    /**
     * Handles a reference to a user account that does not exist.
     *
     * <p><b>404 NOT FOUND</b>, for the same reason as a missing book: the
     * request was well formed, the thing it names is simply not there.</p>
     */
    @ExceptionHandler(UserNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleUserNotFound(UserNotFoundException exception) {
        ErrorResponse errorResponse = new ErrorResponse(
                HttpStatus.NOT_FOUND.value(),
                exception.getMessage(),
                LocalDateTime.now());

        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(errorResponse);
    }

    /**
     * Handles an attempt to issue a book with no copies left on the shelf.
     *
     * <p><b>409 CONFLICT</b>, and the code is chosen carefully. It is not a 400 -
     * the request is valid. It is not a 404 - the book exists. What fails is a
     * clash with the current state of the data: every copy is out on loan, and
     * the identical request will succeed once one is returned. That is exactly
     * what 409 means, and it is the same reasoning as
     * {@link CategoryInUseException}.</p>
     */
    @ExceptionHandler(BookNotAvailableException.class)
    public ResponseEntity<ErrorResponse> handleBookNotAvailable(BookNotAvailableException exception) {
        ErrorResponse errorResponse = new ErrorResponse(
                HttpStatus.CONFLICT.value(),
                exception.getMessage(),
                LocalDateTime.now());

        return ResponseEntity.status(HttpStatus.CONFLICT).body(errorResponse);
    }

    /**
     * Handles a reference to a transaction that does not exist.
     *
     * <p><b>404 NOT FOUND</b>, matching the other not-found handlers: the
     * request was well formed, the record it names is simply not there.</p>
     */
    @ExceptionHandler(TransactionNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleTransactionNotFound(TransactionNotFoundException exception) {
        ErrorResponse errorResponse = new ErrorResponse(
                HttpStatus.NOT_FOUND.value(),
                exception.getMessage(),
                LocalDateTime.now());

        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(errorResponse);
    }

    /**
     * Handles an attempt to return a book that cannot be returned.
     *
     * <p><b>409 CONFLICT</b>. Not a 400 - the request is valid. Not a 404 - the
     * transaction exists. The clash is with the current state of the data,
     * usually a loan that has already been closed, which is exactly what 409
     * describes. Same reasoning as {@link CategoryInUseException} and
     * {@link BookNotAvailableException}.</p>
     */
    @ExceptionHandler(ReturnBookNotAllowedException.class)
    public ResponseEntity<ErrorResponse> handleReturnBookNotAllowed(ReturnBookNotAllowedException exception) {
        ErrorResponse errorResponse = new ErrorResponse(
                HttpStatus.CONFLICT.value(),
                exception.getMessage(),
                LocalDateTime.now());

        return ResponseEntity.status(HttpStatus.CONFLICT).body(errorResponse);
    }

    /**
     * Handles a constraint violation on a controller method parameter.
     *
     * <p>Distinct from {@link MethodArgumentNotValidException}, which covers a
     * {@code @Valid} request <b>body</b>. This one fires when a constraint sits
     * directly on a parameter - {@code @Positive} on a path variable, for
     * instance - which Spring validates itself from 6.1 onwards.</p>
     *
     * <p><b>400 BAD REQUEST</b>. Without this handler the failure still gives a
     * 400, but in Spring's own format complete with a {@code trace} field
     * carrying the stack - the leak closed in the missing-parameter step. This
     * puts it back into the shared {@link ErrorResponse} shape.</p>
     *
     * <p>Several parameters can fail at once, so the messages are joined the
     * same way the body-validation handler joins its field errors.</p>
     */
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ErrorResponse> handleHandlerMethodValidation(HandlerMethodValidationException exception) {
        String message = exception.getAllErrors()
                .stream()
                .map(MessageSourceResolvable::getDefaultMessage)
                .collect(Collectors.joining("; "));

        ErrorResponse errorResponse = new ErrorResponse(
                HttpStatus.BAD_REQUEST.value(),
                message,
                LocalDateTime.now());

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorResponse);
    }

    /**
     * Handles a path or query value that cannot be converted to the type the
     * controller expects.
     *
     * <p>The case that matters here is an unrecognised enum - asking for
     * {@code /api/transactions/status/BANANA} when only ISSUED, RETURNED and
     * OVERDUE exist. It also covers a non-numeric id such as
     * {@code /api/transactions/abc}.</p>
     *
     * <p><b>400 BAD REQUEST</b>: the server is fine, the value is not. Left
     * unhandled this still produced a 400, but in Spring's own format complete
     * with a {@code trace} field - the same leak closed for missing request
     * parameters. This restores the shared {@link ErrorResponse} shape.</p>
     *
     * <p>When the target type is an enum the message lists the values that are
     * accepted, sorted so the text is stable, which turns a dead end into a
     * usable correction. The rejected value is echoed back as data only - it is
     * never interpreted.</p>
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException exception) {
        Class<?> requiredType = exception.getRequiredType();

        String message = "Invalid value for '" + exception.getName() + "': " + exception.getValue();

        if (requiredType != null && requiredType.isEnum()) {
            String allowed = Arrays.stream(requiredType.getEnumConstants())
                    .map(String::valueOf)
                    .sorted()
                    .collect(Collectors.joining(", "));
            message = message + ". Allowed values are: " + allowed;
        }

        ErrorResponse errorResponse = new ErrorResponse(
                HttpStatus.BAD_REQUEST.value(),
                message,
                LocalDateTime.now());

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorResponse);
    }

    /**
     * Last line of defence for a database constraint or data failure that
     * nothing more specific caught.
     *
     * <p>Most integrity problems are anticipated and reported precisely -
     * {@link DuplicateIsbnException}, {@link DuplicateCategoryException},
     * {@link CategoryInUseException} - because the service checks for them
     * before writing. This handler exists for the ones that slip past: a value
     * the column cannot store, a constraint added later, a race between two
     * requests that both passed their checks.</p>
     *
     * <p><b>409 CONFLICT</b>, for the same reason as the specific integrity
     * exceptions above: the request was well formed and the server is
     * healthy - it clashes with the state or the rules of the stored data.</p>
     *
     * <p>The message is a fixed sentence and deliberately <b>does not</b> use
     * {@code exception.getMessage()}. That message carries the failing SQL, its
     * parameter placeholders, the table and column names and the constraint
     * identifier. Returning it would hand a caller a map of the schema, and
     * left unhandled this exception did exactly that - a 500 whose body
     * contained the full INSERT and a Hibernate stack trace. The detail belongs
     * in the server log, not in the response.</p>
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleDataIntegrityViolation(DataIntegrityViolationException exception) {
        ErrorResponse errorResponse = new ErrorResponse(
                HttpStatus.CONFLICT.value(),
                "Database operation could not be completed because it conflicts with existing data"
                        + " or database constraints.",
                LocalDateTime.now());

        return ResponseEntity.status(HttpStatus.CONFLICT).body(errorResponse);
    }
}
