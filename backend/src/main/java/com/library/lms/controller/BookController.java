package com.library.lms.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.library.lms.dto.BookRequest;
import com.library.lms.dto.BookResponse;
import com.library.lms.dto.PagedResponse;
import com.library.lms.service.BookService;

import jakarta.validation.Valid;

/**
 * REST endpoints for managing books.
 *
 * <p>This is the outermost layer. Its only job is translation: turn an incoming
 * HTTP request into a call on {@link BookService}, then turn the answer back
 * into an HTTP response. Deliberately there is no business logic here - no
 * "does this book exist" checks, no field copying. That already lives in the
 * service, and duplicating it would mean two places to keep in step.</p>
 *
 * <p>Since the DTOs were introduced, the Book entity no longer appears in this
 * file at all. Requests arrive as {@link BookRequest} and answers leave as
 * {@link BookResponse}, so the database structure is never exposed directly to
 * a client.</p>
 *
 * <p>{@code @RestController} combines {@code @Controller} with
 * {@code @ResponseBody}, which tells Spring that whatever a method returns is
 * the response body itself rather than the name of a page to render. Jackson is
 * on the classpath via spring-boot-starter-web, so a returned object becomes
 * JSON automatically and an incoming JSON body becomes a Java object.</p>
 *
 * <p>{@code @RequestMapping("/api/books")} is the shared prefix for every method
 * below, so the five endpoints are:</p>
 * <ul>
 *   <li>GET    /api/books      - list every book</li>
 *   <li>GET    /api/books/1    - fetch one book</li>
 *   <li>POST   /api/books      - add a book</li>
 *   <li>PUT    /api/books/1    - replace a book's details</li>
 *   <li>DELETE /api/books/1    - remove a book</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/books")
public class BookController {

    /**
     * Injected through the constructor, exactly as BookService injects its own
     * repository: the field stays final and the class can be built by hand in a
     * test. With only one constructor present, Spring uses it automatically and
     * no {@code @Autowired} annotation is required.
     */
    private final BookService bookService;

    public BookController(BookService bookService) {
        this.bookService = bookService;
    }

    /**
     * GET /api/books?page=0&size=10 - returns one page of books.
     *
     * <p>Both parameters are optional. {@code defaultValue} supplies page 0 and
     * size 10 when they are missing, so a plain {@code GET /api/books} still
     * works and simply returns the first page rather than the entire table.</p>
     *
     * <p>Paging is <b>zero-based</b>: page 0 is the first page, page 1 the
     * second. That matches Spring Data and is the usual convention for an API,
     * though it is worth remembering if a UI wants to show "Page 1".</p>
     *
     * <p>Out-of-range values become a 400 through the existing error handling.
     * A page beyond the last one is <i>not</i> an error - it answers 200 with an
     * empty {@code content} array and the real totals, so a client can tell the
     * difference between "you asked for something impossible" and "you have
     * simply reached the end".</p>
     *
     * <p>{@code sortBy} and {@code direction} are optional too, defaulting to
     * id ascending so an unadorned request behaves exactly as it did before.
     * Only a fixed set of fields may be sorted on - the service checks the name
     * against an allowlist rather than passing it to the query - and anything
     * else is a 400 naming the fields that are allowed. Direction accepts asc or
     * desc in any capitalisation.</p>
     *
     * <p>{@code keyword} and {@code categoryId} are optional filters, and they
     * combine with AND. {@code keyword} matches any of title, author or ISBN;
     * {@code categoryId} restricts to one category. Given both, a book must
     * satisfy the category <i>and</i> match the keyword somewhere. They have no
     * {@code defaultValue}: left out, the parameter arrives as null and no
     * filter is applied, which is why a plain {@code GET /api/books} still
     * lists everything.</p>
     *
     * <p>The totals in the response describe the filtered set. Searching 25
     * books and matching 7 reports {@code totalElements: 7}, not 25.</p>
     */
    @GetMapping
    public ResponseEntity<PagedResponse<BookResponse>> getAllBooks(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "id") String sortBy,
            @RequestParam(defaultValue = "asc") String direction,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Long categoryId,
            Authentication authentication) {
        PagedResponse<BookResponse> books =
                bookService.getAllBooks(page, size, sortBy, direction, keyword, categoryId,
                        authentication.getName());
        return ResponseEntity.ok(books);
    }

    /**
     * GET /api/books/{id} - returns the single book with this id.
     *
     * <p>{@code @PathVariable} binds the {1} in the URL to the method argument.
     * Spring converts the text to a Long for us.</p>
     *
     * <p>If the id is not in the database the service throws
     * BookNotFoundException, which GlobalExceptionHandler turns into a 404.</p>
     */
    @GetMapping("/{id}")
    public ResponseEntity<BookResponse> getBookById(@PathVariable Long id, Authentication authentication) {
        BookResponse book = bookService.getBookById(id, authentication.getName());
        return ResponseEntity.ok(book);
    }

    /**
     * GET /api/books/search?keyword=... - free-text search.
     *
     * <p>{@code @RequestParam} reads the keyword from the query string rather
     * than the path, which suits an optional-feeling search term better than a
     * path variable would. It is required here: asking to search with no
     * keyword is a malformed request, and Spring answers 400 by itself.</p>
     *
     * <p>Note the literal {@code /search} does not clash with
     * {@code /api/books/{id}} - Spring always prefers an exact path segment
     * over a variable one.</p>
     *
     * <p>Always 200 OK, with an empty array when nothing matches. "No results"
     * is a successful search, not a 404.</p>
     */
    @GetMapping("/search")
    public ResponseEntity<List<BookResponse>> searchBooks(@RequestParam String keyword,
                                                         Authentication authentication) {
        List<BookResponse> books = bookService.searchBooks(keyword, authentication.getName());
        return ResponseEntity.ok(books);
    }

    /**
     * GET /api/books/category/{category} - every book on one shelf.
     *
     * <p>The category sits in the path because it identifies a group of books,
     * the way an id identifies one book. Two segments deep, so there is no
     * ambiguity with {@code /api/books/{id}} either.</p>
     *
     * <p>Also always 200 OK with a possibly empty array.</p>
     */
    @GetMapping("/category/{category}")
    public ResponseEntity<List<BookResponse>> getBooksByCategory(@PathVariable String category,
                                                                 Authentication authentication) {
        List<BookResponse> books = bookService.getBooksByCategory(category, authentication.getName());
        return ResponseEntity.ok(books);
    }

    /**
     * POST /api/books - adds a new book.
     *
     * <p>{@code @RequestBody} tells Spring to read the JSON sent by the client
     * and build a {@link BookRequest} from it. {@code @Valid} is what makes the
     * rules declared on that class actually run: Hibernate Validator checks the
     * object <b>before</b> this method body starts, so a blank title or a
     * negative copy count never reaches the service. A failed check produces a
     * 400 Bad Request instead.</p>
     *
     * <p>The reply is <b>201 CREATED</b> rather than 200 OK, because a new
     * resource now exists that did not before - that is what the status code is
     * for. The returned BookResponse carries the id the database generated.</p>
     */
    @PostMapping
    public ResponseEntity<BookResponse> createBook(@Valid @RequestBody BookRequest bookRequest,
                                                  Authentication authentication) {
        BookResponse createdBook = bookService.createBook(bookRequest, authentication.getName());
        return ResponseEntity.status(HttpStatus.CREATED).body(createdBook);
    }

    /**
     * PUT /api/books/{id} - replaces the details of an existing book.
     *
     * <p>Takes the id from the URL and the new values from the JSON body, which
     * is validated by {@code @Valid} in the same way as on POST. Because
     * BookRequest has no id field, the URL is necessarily the authority on
     * which row is edited.</p>
     *
     * <p>200 OK with the updated book is the right answer here: nothing new was
     * created, an existing resource was modified.</p>
     */
    @PutMapping("/{id}")
    public ResponseEntity<BookResponse> updateBook(@PathVariable Long id,
                                                   @Valid @RequestBody BookRequest bookRequest,
                                                   Authentication authentication) {
        BookResponse updatedBook = bookService.updateBook(id, bookRequest, authentication.getName());
        return ResponseEntity.ok(updatedBook);
    }

    /**
     * DELETE /api/books/{id} - removes a book.
     *
     * <p>Answers <b>204 NO CONTENT</b>: the delete succeeded and there is
     * deliberately nothing to send back. That is why the type parameter is
     * {@code Void} and the body is left empty.</p>
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteBook(@PathVariable Long id, Authentication authentication) {
        bookService.deleteBook(id, authentication.getName());
        return ResponseEntity.noContent().build();
    }
}
