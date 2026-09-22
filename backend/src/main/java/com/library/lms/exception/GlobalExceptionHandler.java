package com.library.lms.exception;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

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

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

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
     * Handles a delete refused because the book still has loan history.
     *
     * <p>The same situation as {@link CategoryInUseException} and answered the
     * same way: the request is well formed and the book exists, so <b>409</b>
     * rather than 400 or 404 - it clashes with the state of the stored data.</p>
     *
     * <p>Uses the shared {@link ErrorResponse}. The message comes from the
     * exception, which names the book by id and title and nothing else - no
     * table, no constraint, no count of how many loans there were.</p>
     */
    @ExceptionHandler(BookInUseException.class)
    public ResponseEntity<ErrorResponse> handleBookInUse(BookInUseException exception) {
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
    /**
     * Handles a copy count that would contradict the loans already recorded.
     *
     * <p>400, because the caller asked for something arithmetically impossible
     * rather than the server failing. The message comes from the exception,
     * which carries only counts - how many copies were requested and how many
     * are out - so a client can correct the request without learning anything
     * about the row, the query or the schema.</p>
     *
     * @param exception the rejected copy count
     * @return 400 with the business explanation
     */
    @ExceptionHandler(InvalidCopyCountException.class)
    public ResponseEntity<ErrorResponse> handleInvalidCopyCount(InvalidCopyCountException exception) {
        ErrorResponse errorResponse = new ErrorResponse(
                HttpStatus.BAD_REQUEST.value(),
                exception.getMessage(),
                LocalDateTime.now());

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorResponse);
    }

    /**
     * Handles a due date that cannot follow from the loan's issue date.
     *
     * <p><b>400 BAD REQUEST</b>, like {@link InvalidCopyCountException} above:
     * the caller asked for something that cannot be recorded, and nothing is
     * wrong on the server.</p>
     *
     * <p>Uses the shared {@link ErrorResponse}. The message carries the two
     * dates and nothing else - no account, no book, no query.</p>
     */
    @ExceptionHandler(InvalidDueDateException.class)
    public ResponseEntity<ErrorResponse> handleInvalidDueDate(InvalidDueDateException exception) {
        ErrorResponse errorResponse = new ErrorResponse(
                HttpStatus.BAD_REQUEST.value(),
                exception.getMessage(),
                LocalDateTime.now());

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorResponse);
    }

    @ExceptionHandler(InvalidSortException.class)
    public ResponseEntity<ErrorResponse> handleInvalidSort(InvalidSortException exception) {
        ErrorResponse errorResponse = new ErrorResponse(
                HttpStatus.BAD_REQUEST.value(),
                exception.getMessage(),
                LocalDateTime.now());

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorResponse);
    }

    /**
     * Handles an administrator's attempt to disable or lock their own account.
     *
     * <p><b>400 BAD REQUEST</b>, like the other requests this API refuses on
     * their content: the account exists and the caller may change others, but
     * this change is one no administrator may make to themselves. The message is
     * the exception's fixed sentence.</p>
     */
    @ExceptionHandler(SelfLockoutException.class)
    public ResponseEntity<ErrorResponse> handleSelfLockout(SelfLockoutException exception) {
        ErrorResponse errorResponse = new ErrorResponse(
                HttpStatus.BAD_REQUEST.value(),
                exception.getMessage(),
                LocalDateTime.now());

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorResponse);
    }

    /**
     * Handles an administrator trying to reset their own password through the
     * staff reset. A 400 that says where to go instead: the message is fixed
     * text and names the self-service endpoint, nothing about the account.
     */
    @ExceptionHandler(SelfPasswordResetException.class)
    public ResponseEntity<ErrorResponse> handleSelfPasswordReset(SelfPasswordResetException exception) {
        ErrorResponse errorResponse = new ErrorResponse(
                HttpStatus.BAD_REQUEST.value(),
                exception.getMessage(),
                LocalDateTime.now());

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorResponse);
    }

    /**
     * Handles a password reset the caller's role does not allow - a librarian
     * naming a member of staff, or a member naming anyone. A fixed literal, like
     * the other service-level refusals, so nothing about the target account can
     * reach the response.
     */
    @ExceptionHandler(PasswordResetNotAllowedException.class)
    public ResponseEntity<ErrorResponse> handlePasswordResetNotAllowed(PasswordResetNotAllowedException exception) {
        ErrorResponse errorResponse = new ErrorResponse(
                HttpStatus.FORBIDDEN.value(),
                "Access denied",
                LocalDateTime.now());

        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(errorResponse);
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
    /**
     * Handles a book being issued to an account that cannot borrow it.
     *
     * <p><b>400 BAD REQUEST</b>: the request is well formed and the server is
     * fine - the account named simply is not one a book may be lent to. The
     * exception's own message is used, and it is a fixed sentence that covers
     * every reason equally, so this reply cannot be used to discover whether a
     * particular account is staff, disabled or locked.</p>
     */
    /**
     * Handles a library created under a name another library already has.
     *
     * <p><b>400 BAD REQUEST</b>, the same answer as a duplicate ISBN, category
     * or account. The message is the exception's fixed sentence and does not
     * repeat the name: the caller knows what they sent, and a response is no
     * place to reflect a client's input back.</p>
     */
    @ExceptionHandler(DuplicateLibraryException.class)
    public ResponseEntity<ErrorResponse> handleDuplicateLibrary(DuplicateLibraryException exception) {
        ErrorResponse errorResponse = new ErrorResponse(
                HttpStatus.BAD_REQUEST.value(),
                exception.getMessage(),
                LocalDateTime.now());

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorResponse);
    }

    /**
     * Handles an account that cannot be created under the name or email asked
     * for.
     *
     * <p><b>400 BAD REQUEST</b>, matching how this API already answers a
     * duplicate ISBN or category. The message is the exception's own fixed
     * sentence, which names neither which of the two fields clashed nor the
     * value - both columns are unique across every library, so a specific reply
     * would describe an account the caller may have no business knowing
     * about.</p>
     */
    @ExceptionHandler(DuplicateAccountException.class)
    public ResponseEntity<ErrorResponse> handleDuplicateAccount(DuplicateAccountException exception) {
        ErrorResponse errorResponse = new ErrorResponse(
                HttpStatus.BAD_REQUEST.value(),
                exception.getMessage(),
                LocalDateTime.now());

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorResponse);
    }

    /**
     * Handles an attempt to create an account with a role that cannot be
     * granted.
     *
     * <p><b>400 BAD REQUEST</b>: the request is well formed, the role simply is
     * not one this endpoint hands out. The message names the roles that are
     * allowed, which is the part a caller can act on.</p>
     */
    @ExceptionHandler(RoleNotAssignableException.class)
    public ResponseEntity<ErrorResponse> handleRoleNotAssignable(RoleNotAssignableException exception) {
        ErrorResponse errorResponse = new ErrorResponse(
                HttpStatus.BAD_REQUEST.value(),
                exception.getMessage(),
                LocalDateTime.now());

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorResponse);
    }

    /**
     * Handles a password change where the current password was wrong.
     *
     * <p><b>400, not 401.</b> The caller is authenticated and their session is
     * perfectly valid - answering 401 would suggest logging in again, which
     * would not help. Saying plainly that the current password was wrong
     * discloses nothing: the caller has already proved they are this account.</p>
     */
    @ExceptionHandler(InvalidCurrentPasswordException.class)
    public ResponseEntity<ErrorResponse> handleInvalidCurrentPassword(
            InvalidCurrentPasswordException exception) {
        ErrorResponse errorResponse = new ErrorResponse(
                HttpStatus.BAD_REQUEST.value(),
                exception.getMessage(),
                LocalDateTime.now());

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorResponse);
    }

    @ExceptionHandler(MemberNotEligibleException.class)
    public ResponseEntity<ErrorResponse> handleMemberNotEligible(MemberNotEligibleException exception) {
        ErrorResponse errorResponse = new ErrorResponse(
                HttpStatus.BAD_REQUEST.value(),
                exception.getMessage(),
                LocalDateTime.now());

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorResponse);
    }

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
     * Handles a fine payment the loan's state will not allow.
     *
     * <p><b>409 CONFLICT</b>, the same reasoning as a refused return: the request
     * is valid and the loan exists, but the book is still out, the fine is
     * already paid, or nothing is owed. The message is the exception's fixed
     * sentence for that case.</p>
     */
    @ExceptionHandler(FinePaymentNotAllowedException.class)
    public ResponseEntity<ErrorResponse> handleFinePaymentNotAllowed(FinePaymentNotAllowedException exception) {
        ErrorResponse errorResponse = new ErrorResponse(
                HttpStatus.CONFLICT.value(),
                exception.getMessage(),
                LocalDateTime.now());

        return ResponseEntity.status(HttpStatus.CONFLICT).body(errorResponse);
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
     * usable correction. The rejected value itself is <b>not</b> repeated back:
     * it is whatever the caller put in the URL, of any length and any content,
     * and a client that renders error messages as HTML would render it. The
     * parameter's name is the part that helps, and that is declared by the
     * controller rather than sent by the caller.</p>
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException exception) {
        Class<?> requiredType = exception.getRequiredType();

        // The parameter's name, never the value that was sent. The value is
        // whatever the caller put in the URL, of any length and any content,
        // and echoing it back is how a client that renders error messages as
        // HTML ends up rendering someone else's markup. The name, and for an
        // enum the list of values that would have worked, is what a caller
        // needs to fix the request.
        String message = "Invalid value for '" + exception.getName() + "'";

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
     * Handles a request body Jackson could not read at all.
     *
     * <p><b>400 BAD REQUEST</b>, not 500. Left unhandled this fell through to
     * the catch-all and reported a malformed body as a server fault - which is
     * both wrong and unhelpful, since the one thing the caller needs to know is
     * that the fault is theirs and retrying unchanged will not help. Truncated
     * JSON, a stray brace or an empty body all arrive here.</p>
     *
     * <p>The message is a fixed sentence and deliberately does <b>not</b> use
     * {@code exception.getMessage()}. Jackson's text names the target class, the
     * field it was parsing and the byte offset it gave up at - a description of
     * the server's internals handed to whoever sent the bad request.</p>
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadableBody(HttpMessageNotReadableException exception) {
        ErrorResponse errorResponse = new ErrorResponse(
                HttpStatus.BAD_REQUEST.value(),
                "Request body could not be read. Check that it is valid JSON.",
                LocalDateTime.now());

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorResponse);
    }

    /**
     * Handles a request whose Content-Type this API cannot consume.
     *
     * <p><b>415 UNSUPPORTED MEDIA TYPE</b> - the status that exists for exactly
     * this, and the one a client can act on: the body may be perfectly valid, it
     * was simply announced as the wrong format. Previously a 500.</p>
     *
     * <p>The message names the type this API accepts rather than echoing what
     * was sent or listing internal converter details.</p>
     */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleUnsupportedMediaType(
            HttpMediaTypeNotSupportedException exception) {
        ErrorResponse errorResponse = new ErrorResponse(
                HttpStatus.UNSUPPORTED_MEDIA_TYPE.value(),
                "Unsupported content type. This API accepts application/json.",
                LocalDateTime.now());

        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE).body(errorResponse);
    }

    /**
     * Handles a request that names a real path with the wrong verb.
     *
     * <p><b>405 METHOD NOT ALLOWED</b>, not 500. Spring raises this before any
     * controller code runs - the path matched a mapping, the method did not -
     * so nothing in the application has even been asked to do anything yet.
     * Left unhandled it fell through to the catch-all and reported a server
     * fault for what is a client using the wrong verb, typically a DELETE where
     * the API wanted a POST.</p>
     *
     * <p>The message is a fixed sentence. The exception's own text names the
     * rejected method and lists the ones the mapping does support, which is a
     * description of the API's shape handed to whoever probed it.</p>
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethodNotAllowed(
            HttpRequestMethodNotSupportedException exception) {
        ErrorResponse errorResponse = new ErrorResponse(
                HttpStatus.METHOD_NOT_ALLOWED.value(),
                "Method not allowed.",
                LocalDateTime.now());

        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(errorResponse);
    }

    /**
     * Handles a caller who will not accept anything this API can produce.
     *
     * <p><b>406 NOT ACCEPTABLE.</b> The counterpart of the 415 above: there the
     * body arrived in a format we cannot read, here the caller's
     * {@code Accept} header rules out every format we can write. Nothing is
     * broken in either case.</p>
     *
     * <p><b>Why this one pins its own content type.</b> Every other handler in
     * this class lets content negotiation choose how to serialise the
     * {@link ErrorResponse}. That cannot work here, because negotiation is
     * precisely what has already failed - asking it to serialise the
     * explanation would fail for the same reason and the caller would receive a
     * bare 406 with an empty body, which is what this API did before this
     * handler existed. Declaring {@code application/json} explicitly makes the
     * converter write it regardless. Answering with a representation the client
     * did not ask for is expressly allowed for an error response, and a
     * readable explanation is worth more to a caller than a silent refusal.</p>
     *
     * <p>The message names no media type, neither the one requested nor the
     * ones available.</p>
     */
    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    public ResponseEntity<ErrorResponse> handleNotAcceptable(
            HttpMediaTypeNotAcceptableException exception) {
        ErrorResponse errorResponse = new ErrorResponse(
                HttpStatus.NOT_ACCEPTABLE.value(),
                "Requested representation is not available.",
                LocalDateTime.now());

        return ResponseEntity.status(HttpStatus.NOT_ACCEPTABLE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(errorResponse);
    }

    /**
     * Handles a request for a path this application does not serve.
     *
     * <p><b>404 NOT FOUND.</b> When no mapping matches, the request falls
     * through to the static resource handler, which finds no file either and
     * raises this. Left unhandled it reached the catch-all, so every typo in a
     * URL - and every scan for {@code /.env} or {@code /admin} - came back as
     * <b>500</b>. That is worse than merely wrong: a server reporting itself
     * broken for any unknown path tells a prober that something is there to
     * break.</p>
     *
     * <p>The message matches the one already used for a missing resource
     * elsewhere in this class rather than echoing the requested path, which
     * would reflect attacker-controlled text straight back into the
     * response.</p>
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoResourceFound(NoResourceFoundException exception) {
        ErrorResponse errorResponse = new ErrorResponse(
                HttpStatus.NOT_FOUND.value(),
                "Resource not found.",
                LocalDateTime.now());

        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(errorResponse);
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
        // Where the detail this response withholds actually goes. These are
        // rare enough not to be noise, and the constraint that failed is the
        // whole diagnosis.
        log.warn("Database integrity violation; answering with a generic 409", exception);

        ErrorResponse errorResponse = new ErrorResponse(
                HttpStatus.CONFLICT.value(),
                "Database operation could not be completed because it conflicts with existing data"
                        + " or database constraints.",
                LocalDateTime.now());

        return ResponseEntity.status(HttpStatus.CONFLICT).body(errorResponse);
    }

    /**
     * Handles two requests that changed the same row at the same time.
     *
     * <p>{@code Book} and {@code Transaction} carry a {@code @Version} column,
     * so every update is written as
     * {@code UPDATE ... WHERE id = ? AND version = ?}. When two requests read a
     * row, both decide what to write and both try to save it, the first commits
     * and bumps the version; the second matches no row, and Hibernate raises
     * this exception rather than letting the write disappear.</p>
     *
     * <p>Without a handler here that surfaced as a <b>500</b> through the
     * catch-all, which reads as a server fault. It is not one: nothing is
     * broken, the caller simply lost a race. <b>409 CONFLICT</b> says exactly
     * that, and matches how every other "your request clashes with the stored
     * state" failure in this class is reported.</p>
     *
     * <p>The message is a fixed sentence and invites a retry, because retrying
     * is genuinely the right response - the second attempt reads the row as it
     * now stands. It carries no id, no table name and no version number:
     * echoing them back would describe the row the caller did not manage to
     * write, and the detail belongs in the server log.</p>
     */
    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<ErrorResponse> handleOptimisticLockingFailure(
            ObjectOptimisticLockingFailureException exception) {
        ErrorResponse errorResponse = new ErrorResponse(
                HttpStatus.CONFLICT.value(),
                "The resource was modified by another request. Please try again.",
                LocalDateTime.now());

        return ResponseEntity.status(HttpStatus.CONFLICT).body(errorResponse);
    }

    /**
     * Handles a refresh token that cannot be used.
     *
     * <p><b>401</b>: the credential offered was not accepted. One fixed sentence
     * for every reason - unknown, already used, revoked at logout, expired, or
     * belonging to an account that is disabled or locked - so the answer cannot
     * be used to find out which.</p>
     */
    /**
     * Handles a password reset token that cannot be redeemed. One fixed 400 for
     * every reason, so the answer does not say whether a token ever existed.
     */
    @ExceptionHandler(InvalidPasswordResetTokenException.class)
    public ResponseEntity<ErrorResponse> handleInvalidPasswordResetToken(
            InvalidPasswordResetTokenException exception) {
        ErrorResponse errorResponse = new ErrorResponse(
                HttpStatus.BAD_REQUEST.value(),
                exception.getMessage(),
                LocalDateTime.now());

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorResponse);
    }

    @ExceptionHandler(InvalidRefreshTokenException.class)
    public ResponseEntity<ErrorResponse> handleInvalidRefreshToken(InvalidRefreshTokenException exception) {
        ErrorResponse errorResponse = new ErrorResponse(
                HttpStatus.UNAUTHORIZED.value(),
                exception.getMessage(),
                LocalDateTime.now());

        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(errorResponse);
    }

    /**
     * Handles a login attempt whose credentials do not check out.
     *
     * <p>{@link AuthenticationException} is Spring Security's base type for
     * every way authentication can fail, so this single handler covers a wrong
     * password, an unknown account, a disabled account and the rest.</p>
     *
     * <p><b>401, not 500.</b> Without this handler the exception fell through
     * to the catch-all below, and the API reported a server fault for what is
     * an ordinary client outcome. 401 Unauthorized says the credentials were
     * rejected, which is what actually happened.</p>
     *
     * <p><b>One message for every failure.</b> The text is fixed and says only
     * that the pair was wrong. Separating "no such user" from "wrong password"
     * would let an attacker confirm which accounts exist, one guess at a time,
     * before trying a single password against them. The exception's own message
     * is never read for the same reason: it can name the username and the
     * reason, and neither belongs in a response.</p>
     *
     * @param exception the authentication failure, deliberately never read
     * @return 401 with a message that reveals nothing about the account
     */
    /**
     * Handles an authenticated caller reaching for data that is not theirs.
     *
     * <p><b>403, not 404.</b> The caller is known and their token is valid;
     * what they lack is permission. 401 would wrongly suggest logging in again
     * would help, and 404 would answer a different question than the one asked
     * - and answering it would leak, because "no such user" and "not your user"
     * must look identical from outside.</p>
     *
     * <p>The message is a fixed literal rather than the exception's own text.
     * The exception already carries exactly this string, so today the two agree;
     * hard-coding it here means that if someone later adds an id or a username
     * to that exception for debugging, the detail still cannot reach a
     * response.</p>
     *
     * @param exception the refusal, deliberately never read
     * @return 403 with a message that describes nothing about the target
     */
    @ExceptionHandler(TransactionAccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleTransactionAccessDenied(
            TransactionAccessDeniedException exception) {
        ErrorResponse errorResponse = new ErrorResponse(
                HttpStatus.FORBIDDEN.value(),
                "Access denied",
                LocalDateTime.now());

        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(errorResponse);
    }

    /**
     * Handles a librarian asking the user directory for staff accounts.
     *
     * <p>Librarians may see members and nothing else. The same fixed literal as
     * the transaction refusal, for the same reason: the response names nothing
     * about the accounts that were asked for.</p>
     *
     * @param exception the refusal, deliberately never read
     * @return 403 with a message that describes nothing about the target
     */
    @ExceptionHandler(UserDirectoryAccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleUserDirectoryAccessDenied(
            UserDirectoryAccessDeniedException exception) {
        ErrorResponse errorResponse = new ErrorResponse(
                HttpStatus.FORBIDDEN.value(),
                "Access denied",
                LocalDateTime.now());

        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(errorResponse);
    }

    /**
     * Handles a librarian or member asking to read the audit log.
     *
     * <p>The log is an administrator's view of their own library. The same
     * fixed literal as the refusals above, for the same reason: the response
     * says nothing about what the log holds, or whether it holds anything.</p>
     *
     * @param exception the refusal, deliberately never read
     * @return 403 with a message that describes nothing about the log
     */
    /**
     * Handles a payment whose answer did not verify.
     *
     * <p>One fixed sentence for every reason: a signature that is not the
     * provider's, an order belonging to another library or another loan, or a
     * payment reference that does not match the one that succeeded. Which of
     * those it was is exactly what someone forging a payment needs, and it
     * would let them find a working combination one field at a time. The fine
     * is untouched, and the attempt is recorded as a failed payment.</p>
     *
     * @param exception the refusal, deliberately never read
     * @return 400 with a message that describes nothing about what failed
     */
    @ExceptionHandler(PaymentVerificationFailedException.class)
    public ResponseEntity<ErrorResponse> handlePaymentVerificationFailed(
            PaymentVerificationFailedException exception) {
        ErrorResponse errorResponse = new ErrorResponse(
                HttpStatus.BAD_REQUEST.value(),
                "The payment could not be verified.",
                LocalDateTime.now());

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorResponse);
    }

    /**
     * Handles an online payment asked for where no gateway is configured.
     *
     * <p>503 rather than 500: nothing is broken, the deployment simply has no
     * provider credentials, and fines can still be recorded at the desk. The
     * message names no setting - what is missing is a deployment's business,
     * not a caller's.</p>
     *
     * @param exception the refusal, deliberately never read
     * @return 503 with a message that names nothing about the configuration
     */
    @ExceptionHandler(PaymentGatewayNotConfiguredException.class)
    public ResponseEntity<ErrorResponse> handlePaymentGatewayNotConfigured(
            PaymentGatewayNotConfiguredException exception) {
        ErrorResponse errorResponse = new ErrorResponse(
                HttpStatus.SERVICE_UNAVAILABLE.value(),
                "Online payment is not available.",
                LocalDateTime.now());

        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(errorResponse);
    }

    /**
     * Handles a provider that could not be reached or would not open an order.
     *
     * <p>503, like the unconfigured case: the request was fine, the provider
     * was not. The message repeats nothing the provider said - a payment
     * provider's error text quotes the request back, and an error response is
     * the last place that belongs. Fines can still be taken at the desk.</p>
     *
     * @param exception the failure, deliberately never read
     * @return 503 with a message that carries nothing from the provider
     */
    @ExceptionHandler(PaymentGatewayUnavailableException.class)
    public ResponseEntity<ErrorResponse> handlePaymentGatewayUnavailable(
            PaymentGatewayUnavailableException exception) {
        ErrorResponse errorResponse = new ErrorResponse(
                HttpStatus.SERVICE_UNAVAILABLE.value(),
                "Online payment is temporarily unavailable.",
                LocalDateTime.now());

        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(errorResponse);
    }

    /**
     * Handles an assistant whose provider could not answer.
     *
     * <p>503, like the payment provider's: the request was fine, the provider
     * was not, and asking again later may work. One sentence for a timeout, a
     * refused key, a rate limit, a failure and an answer with no text in it -
     * which of those it was would tell a caller about the deployment's account
     * with the provider, and none of it is their business.</p>
     *
     * @param exception the failure, deliberately never read
     * @return 503 with a message that carries nothing from the provider
     */
    @ExceptionHandler(AiChatUnavailableException.class)
    public ResponseEntity<ErrorResponse> handleAiChatUnavailable(AiChatUnavailableException exception) {
        ErrorResponse errorResponse = new ErrorResponse(
                HttpStatus.SERVICE_UNAVAILABLE.value(),
                "The assistant is unavailable right now.",
                LocalDateTime.now());

        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(errorResponse);
    }

    @ExceptionHandler(AuditAccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAuditAccessDenied(AuditAccessDeniedException exception) {
        ErrorResponse errorResponse = new ErrorResponse(
                HttpStatus.FORBIDDEN.value(),
                "Access denied",
                LocalDateTime.now());

        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(errorResponse);
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ErrorResponse> handleAuthenticationFailure(AuthenticationException exception) {
        ErrorResponse errorResponse = new ErrorResponse(
                HttpStatus.UNAUTHORIZED.value(),
                "Invalid username or password",
                LocalDateTime.now());

        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(errorResponse);
    }

    /**
     * Last-resort handler for anything no other handler claims.
     *
     * <p>Every handler above names a specific type. This one names
     * {@link Exception}, so it catches whatever is left - a bug in our own
     * code, a driver fault, an error from a library we do not anticipate.</p>
     *
     * <p><b>It does not shadow the handlers above.</b> Spring resolves an
     * exception to the <i>most specific</i> matching handler rather than the
     * first or last declared, so a BookNotFoundException still reaches its own
     * 404 and only genuinely unmatched exceptions arrive here. Position in the
     * file is irrelevant to that.</p>
     *
     * <p><b>500 INTERNAL SERVER ERROR</b>, because by definition we do not know
     * what went wrong. Anything we could have anticipated has its own handler
     * and its own more useful status.</p>
     *
     * <p>The message is a fixed sentence, and the {@code exception} argument is
     * deliberately never read. That is the whole point of this handler: an
     * unexpected exception carries the most revealing text in the system -
     * stack traces, SQL, class names, file paths, sometimes fragments of the
     * data being processed. Without this handler Spring's default error page
     * returned exactly that to the caller. The detail belongs in the server
     * log; the response gets a sentence that says something went wrong and
     * nothing about what.</p>
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpectedException(Exception exception) {
        // The one place the detail is kept. The caller gets the sentence below
        // and nothing else, while the server log gets the exception and its
        // stack trace - without which a 500 is invisible and undiagnosable.
        log.error("Unhandled exception; answering with a generic 500", exception);

        ErrorResponse errorResponse = new ErrorResponse(
                HttpStatus.INTERNAL_SERVER_ERROR.value(),
                "An unexpected error occurred. Please try again later.",
                LocalDateTime.now());

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
    }
}
