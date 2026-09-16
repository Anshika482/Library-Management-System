package com.library.lms.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.library.lms.dto.IssueBookRequest;
import com.library.lms.dto.PagedResponse;
import com.library.lms.dto.TransactionResponse;
import com.library.lms.entity.TransactionStatus;
import com.library.lms.service.TransactionService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;

/**
 * REST endpoints for borrowing and returning books.
 *
 * <p>Issuing, returning and read-only lookups are exposed. Fines and overdue
 * detection come later.</p>
 *
 * <p>Structured exactly like {@link BookController}: a shared
 * {@code @RequestMapping} prefix, constructor injection of the service, and
 * DTOs on both sides of the boundary. There is deliberately no logic here - no
 * availability check, no date arithmetic, no entity handling. The controller
 * unpacks the request, calls the service and sets a status code; the rules live
 * in {@link TransactionService} where a transaction can wrap them.</p>
 */
@RestController
@RequestMapping("/api/transactions")
public class TransactionController {

    private final TransactionService transactionService;

    public TransactionController(TransactionService transactionService) {
        this.transactionService = transactionService;
    }

    /**
     * POST /api/transactions/issue - lends one copy of a book to a user.
     *
     * <p>{@code @Valid} runs the rules on {@link IssueBookRequest} before this
     * method body starts, so a missing or non-positive id never reaches the
     * service. Those failures become a 400 through the validation handler that
     * already exists - no second validation mechanism is introduced here.</p>
     *
     * <p>Answers <b>201 CREATED</b>, because a loan record now exists that did
     * not before. The body is a {@link TransactionResponse} carrying the id the
     * database generated, with the book and borrower as ids only - never the
     * Book or User objects.</p>
     *
     * <p>Three failures are possible and all are handled centrally: an unknown
     * book or user gives 404, and a book with every copy already on loan gives
     * <b>409 CONFLICT</b> - the request is well formed and the book exists, but
     * the current state of the data will not allow it.</p>
     */
    @PostMapping("/issue")
    public ResponseEntity<TransactionResponse> issueBook(@Valid @RequestBody IssueBookRequest issueBookRequest,
            Authentication authentication) {
        TransactionResponse issuedTransaction = transactionService.issueBook(
                issueBookRequest.getBookId(),
                issueBookRequest.getMemberId(),
                authentication.getName(),
                issueBookRequest.getDueDate());

        return ResponseEntity.status(HttpStatus.CREATED).body(issuedTransaction);
    }

    /**
     * POST /api/transactions/{transactionId}/return - takes a borrowed book back.
     *
     * <p>The id is in the path because it identifies the loan being acted on,
     * and there is no request body: closing a loan needs no data beyond which
     * loan it is. POST rather than PUT because this is an action that changes
     * state on the server's terms - the return date is decided here, not sent
     * by the caller.</p>
     *
     * <p>{@code @Positive} rejects 0 and negatives before the service or the
     * database is touched. Spring 6.1 onwards validates constraints on
     * controller parameters itself, so no {@code @Validated} is needed on the
     * class and the project's existing style is unchanged.</p>
     *
     * <p>Answers <b>200 OK</b>, not 201: nothing new was created, an existing
     * loan was updated. The body is the same {@link TransactionResponse} the
     * issue endpoint returns, now carrying a return date and a RETURNED
     * status.</p>
     *
     * <p>Failures are handled centrally: an unknown transaction gives 404, and
     * one that is not in the ISSUED state - already returned, most often -
     * gives <b>409 CONFLICT</b>, because the request is valid and the record
     * exists but the current state will not allow the change.</p>
     *
     * <p>{@link Authentication} is injected by Spring Security and its name is
     * passed straight through, exactly as the issue endpoint does. It is the
     * only way the service learns which library the caller belongs to; a loan
     * in another library then answers 404 rather than being closed.</p>
     */
    @PostMapping("/{transactionId}/return")
    public ResponseEntity<TransactionResponse> returnBook(
            @PathVariable @Positive(message = "Transaction id must be a positive number") Long transactionId,
            Authentication authentication) {
        TransactionResponse returnedTransaction =
                transactionService.returnBook(transactionId, authentication.getName());

        return ResponseEntity.ok(returnedTransaction);
    }

    /**
     * GET /api/transactions/{transactionId} - one loan by its id.
     *
     * <p>200 with the loan, or 404 if there is no such record. {@code @Positive}
     * stops 0 and negatives at the edge, so the service is never called with an
     * id that cannot exist.</p>
     */
    @GetMapping("/{transactionId}")
    public ResponseEntity<TransactionResponse> getTransactionById(
            @PathVariable @Positive(message = "Transaction id must be a positive number") Long transactionId,
            Authentication authentication) {
        TransactionResponse transaction =
                transactionService.getTransactionById(transactionId, authentication.getName());

        return ResponseEntity.ok(transaction);
    }

    /**
     * GET /api/transactions/book/{bookId} - the borrowing history of one book.
     *
     * <p>Returns every loan ever recorded against the book <b>within the
     * caller's library</b>, returned copies included - not just what is out now.
     * Always 200: a book nobody has borrowed gives an empty page, which is an
     * answer rather than an error, and so does a book belonging to another
     * library.</p>
     *
     * <p><b>Paged</b>, on the same four parameters and defaults as
     * {@code /status/{status}} and {@code GET /api/books}. A title that has been
     * on the shelves for years accumulates history without limit, and library
     * scoping bounds whose loans are returned but not how many. All four
     * parameters are optional, so the URL is unchanged.</p>
     */
    @GetMapping("/book/{bookId}")
    public ResponseEntity<PagedResponse<TransactionResponse>> getTransactionsByBook(
            @PathVariable @Positive(message = "Book id must be a positive number") Long bookId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "id") String sortBy,
            @RequestParam(defaultValue = "asc") String direction,
            Authentication authentication) {
        PagedResponse<TransactionResponse> transactions = transactionService.getTransactionsByBook(
                bookId, page, size, sortBy, direction, authentication.getName());

        return ResponseEntity.ok(transactions);
    }

    /**
     * GET /api/transactions/user/{userId} - the borrowing history of one user.
     *
     * <p>Same shape and same reasoning as the book history above, paged on the
     * same four parameters and defaults. Note it exposes only loan records;
     * nothing about the user themselves is returned, not even their name.</p>
     *
     * <p>Unlike the book and status histories, this one is not staff-only at
     * the filter chain - a member may read their own. The rule that decides
     * <i>whose</i> history they may read lives in the service, and is applied
     * before pagination, so an unauthorized request answers 403 rather than
     * commenting on the page size it was sent with.</p>
     */
    @GetMapping("/user/{userId}")
    public ResponseEntity<PagedResponse<TransactionResponse>> getTransactionsByUser(
            @PathVariable @Positive(message = "User id must be a positive number") Long userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "id") String sortBy,
            @RequestParam(defaultValue = "asc") String direction,
            Authentication authentication) {
        PagedResponse<TransactionResponse> transactions = transactionService.getTransactionsByUser(
                userId, page, size, sortBy, direction, authentication.getName());

        return ResponseEntity.ok(transactions);
    }

    /**
     * GET /api/transactions/status/{status} - every loan in one state.
     *
     * <p>Accepts ISSUED, RETURNED or OVERDUE. Declaring the parameter as the
     * {@link TransactionStatus} enum lets Spring reject anything else during
     * conversion, before the service or a query is reached; that failure is
     * turned into a clean 400 by the type-mismatch handler rather than a
     * stack trace.</p>
     *
     * <p>The single-segment {@code /{transactionId}} mapping above is not
     * ambiguous with this one - the paths differ in length, and Spring prefers
     * a literal segment such as "status" over a variable in any case.</p>
     *
     * <p>The result covers the caller's own library only. Without the
     * authenticated name this endpoint listed every loan in every library from
     * a single request, which was the broadest disclosure in the API.</p>
     *
     * <p><b>Paged.</b> The body is a {@link PagedResponse} rather than a bare
     * array, because library scoping bounds whose loans are returned but not
     * how many, and "every open loan" is the largest answer this API gives. The
     * four parameters and their defaults are the ones
     * {@code GET /api/books} already uses, so the two paginated endpoints are
     * driven the same way; all four are optional, so the URL itself is
     * unchanged.</p>
     */
    @GetMapping("/status/{status}")
    public ResponseEntity<PagedResponse<TransactionResponse>> getTransactionsByStatus(
            @PathVariable TransactionStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "id") String sortBy,
            @RequestParam(defaultValue = "asc") String direction,
            Authentication authentication) {
        PagedResponse<TransactionResponse> transactions = transactionService.getTransactionsByStatus(
                status, page, size, sortBy, direction, authentication.getName());

        return ResponseEntity.ok(transactions);
    }
}
