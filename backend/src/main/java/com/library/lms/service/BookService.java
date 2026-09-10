package com.library.lms.service;

import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.library.lms.dto.BookRequest;
import com.library.lms.dto.BookResponse;
import com.library.lms.dto.PagedResponse;
import com.library.lms.entity.Book;
import com.library.lms.entity.Category;
import com.library.lms.entity.Library;
import com.library.lms.entity.User;
import com.library.lms.exception.BookNotFoundException;
import com.library.lms.exception.CategoryNotFoundException;
import com.library.lms.exception.DuplicateIsbnException;
import com.library.lms.exception.InvalidCopyCountException;
import com.library.lms.exception.InvalidPaginationException;
import com.library.lms.exception.InvalidSortException;
import com.library.lms.exception.UserNotFoundException;
import com.library.lms.repository.BookRepository;
import com.library.lms.repository.BookSpecifications;
import com.library.lms.repository.CategoryRepository;
import com.library.lms.repository.UserRepository;

/**
 * Business logic for managing books.
 *
 * <p>This is the middle layer of the application. The repository knows only how
 * to read and write rows; the controller knows only how to translate HTTP into
 * Java calls. Everything in between - the rules about what counts as valid,
 * what happens when a book is missing, which fields an update is allowed to
 * touch - lives here.</p>
 *
 * <p>Since the DTOs were introduced, this layer is also the <b>boundary</b>
 * between the two worlds. It accepts {@link BookRequest} objects from the web
 * side and answers with {@link BookResponse} objects, while {@link Book}, the
 * entity, never leaves this class. That is the point of the DTOs: the shape of
 * the database table and the shape of the API can now change independently.</p>
 *
 * <p>{@code @Service} marks the class as a Spring bean so it is created once at
 * startup and can be injected wherever it is needed.</p>
 */
@Service
public class BookService {

    /**
     * The repository is {@code final} and set in the constructor below.
     *
     * <p>This is <b>constructor injection</b>, the style Spring recommends. It
     * beats putting {@code @Autowired} on the field for two reasons: the field
     * can be final (so it can never be reassigned by accident), and the class
     * can be created in a plain unit test with {@code new BookService(mockRepo)}
     * without starting Spring at all.</p>
     *
     * <p>Note there is no {@code @Autowired} annotation on the constructor. When
     * a Spring bean has exactly one constructor, Spring uses it automatically.</p>
     */
    /**
     * The largest page a client may ask for.
     *
     * <p>Without a ceiling, {@code ?size=1000000} would load the whole table
     * into memory in one request - exactly what pagination exists to prevent.
     * 50 is generous for a list screen and cheap to serve.</p>
     */
    private static final int MAX_PAGE_SIZE = 50;

    /**
     * The only fields a client may sort by, mapped to the property name used in
     * the query.
     *
     * <p>This map is the security boundary for sorting, and the reason it is a
     * map rather than a list of allowed strings is worth being precise about.
     * The value handed to {@code Sort.by} is taken from the <b>right-hand side</b>
     * of this map - a constant written here - never from the request. Even if
     * the lookup key came from somewhere unexpected, an unrecognised name
     * produces no entry and the request is rejected; nothing a client types can
     * ever reach the query as a property path.</p>
     *
     * <p>Without that, {@code sortBy} would be free text interpreted as an
     * entity property. A wrong guess would fail deep inside Hibernate as a
     * PropertyReferenceException, surfacing as a 500 that names the entity's
     * internals - and a determined caller could probe which fields exist.</p>
     *
     * <p>The keys deliberately exclude {@code category}: it is an association
     * rather than a plain column, so sorting by it needs a join and is a
     * separate decision.</p>
     */
    private static final Map<String, String> SORTABLE_FIELDS = Map.of(
            "id", "id",
            "title", "title",
            "author", "author",
            "isbn", "isbn",
            "totalCopies", "totalCopies",
            "availableCopies", "availableCopies");

    private final BookRepository bookRepository;

    /**
     * Needed since categories became rows: turning the {@code categoryId} a
     * client sends into a real {@link Category} requires a lookup. Injected the
     * same way as the book repository, through the one constructor.
     */
    private final CategoryRepository categoryRepository;

    private final UserRepository userRepository;

    public BookService(BookRepository bookRepository, CategoryRepository categoryRepository,
                       UserRepository userRepository) {
        this.bookRepository = bookRepository;
        this.categoryRepository = categoryRepository;
        this.userRepository = userRepository;
    }

    /**
     * Returns one page of books.
     *
     * <p>This no longer loads the whole table. {@code findAll(Pageable)} - which
     * JpaRepository already provides - turns into a {@code LIMIT}/{@code OFFSET}
     * query plus a count, so the amount of work stays the same whether the
     * library holds twenty five books or a million.</p>
     *
     * <p>The sort is not optional. Without an {@code ORDER BY} the database may
     * return rows in any order it likes, and a client paging through the list
     * could then see the same book twice or miss one entirely. Ordering by id
     * ascending makes the sequence deterministic.</p>
     *
     * <p>A page past the end is not an error - it is simply empty. Spring Data
     * returns an empty page with the real totals, so the caller still learns how
     * many books exist and can navigate back.</p>
     *
     * <p>Sorting is chosen by the caller but constrained to a fixed list of
     * fields; see {@link #SORTABLE_FIELDS}. Left unspecified it is id ascending,
     * exactly as before.</p>
     *
     * <p>Both filters are optional and combine with <b>AND</b>: supplying each
     * of them narrows the result further. The keyword itself is an OR across
     * three columns, and that group is bracketed inside its own specification,
     * so the condition really is</p>
     *
     * <pre>category = ? AND (title LIKE ? OR author LIKE ? OR isbn LIKE ?)</pre>
     *
     * <p>The totals reported back describe the <b>filtered</b> set, not the
     * whole table, because the count query carries the same WHERE clause.</p>
     *
     * @param page       zero-based page number, so 0 is the first page
     * @param size       how many books per page, at most {@value #MAX_PAGE_SIZE}
     * @param sortBy     which field to order by, one of the supported names
     * @param direction  "asc" or "desc", case-insensitive
     * @param keyword    optional text to match against title, author or ISBN
     * @param categoryId optional category to restrict to
     * @throws InvalidPaginationException if page or size is out of range
     * @throws InvalidSortException       if the field or direction is unsupported
     * @throws CategoryNotFoundException  if categoryId is given but does not exist
     */
    public PagedResponse<BookResponse> getAllBooks(int page, int size, String sortBy, String direction,
                                                   String keyword, Long categoryId,
                                                   String authenticatedUsername) {
        Long libraryId = libraryIdOf(authenticatedUsername);

        validatePagination(page, size);

        Pageable pageable = PageRequest.of(page, size, resolveSort(sortBy, direction));
        Page<Book> bookPage = bookRepository.findAll(buildFilter(keyword, categoryId, libraryId), pageable);

        List<BookResponse> content = bookPage.getContent()
                .stream()
                .map(this::toResponse)
                .toList();

        return new PagedResponse<>(
                content,
                bookPage.getNumber(),
                bookPage.getSize(),
                bookPage.getTotalElements(),
                bookPage.getTotalPages());
    }

    /**
     * Looks up one book by its primary key.
     *
     * @throws BookNotFoundException if no book has this id
     */
    public BookResponse getBookById(Long id, String authenticatedUsername) {
        return toResponse(findBookOrThrow(id, libraryIdOf(authenticatedUsername)));
    }

    /**
     * Finds books whose title, author or ISBN contains the given text.
     *
     * <p>One search box, three columns. The same keyword is handed to the
     * repository three times because each part of the query needs its own
     * value; doing that here keeps the awkward method name out of the
     * controller.</p>
     *
     * <p>The search is partial and case-insensitive, so "java" finds
     * "Effective Java". No match is not an error - the result is simply an
     * empty list.</p>
     */
    public List<BookResponse> searchBooks(String keyword, String authenticatedUsername) {
        Specification<Book> specification =
                BookSpecifications.belongsToLibrary(libraryIdOf(authenticatedUsername));

        if (hasKeyword(keyword)) {
            specification = specification.and(BookSpecifications.matchesKeyword(keyword.trim()));
        }

        return bookRepository.findAll(specification)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * Returns every book shelved under one category.
     *
     * <p>Uses {@code findByCategory}, the repository method declared back when
     * the interface was written. Unlike the search above this is an exact
     * match, which is what a category filter should be: "Programming" is a
     * shelf label, not a phrase to search within.</p>
     *
     * <p>An unknown category yields an empty list rather than a 404 - asking
     * for a shelf that happens to hold nothing is a valid question with a valid
     * answer.</p>
     */
    public List<BookResponse> getBooksByCategory(String category, String authenticatedUsername) {
        return bookRepository.findByLibraryIdAndCategoryName(libraryIdOf(authenticatedUsername), category)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * Saves a brand new book and returns it.
     *
     * <p>A fresh entity is built from the request, so the client cannot set the
     * id: that belongs to MySQL's AUTO_INCREMENT. The response is built from the
     * <b>saved</b> entity, which is the only copy carrying that generated id.</p>
     *
     * <p>The ISBN is checked first. Passing {@code null} as the "current book"
     * means no existing book is allowed to hold it, which is exactly right for
     * a title that does not exist yet.</p>
     *
     * @throws DuplicateIsbnException if another book already uses this ISBN
     */
    @Transactional
    public BookResponse createBook(BookRequest request, String authenticatedUsername) {
        // The owning library comes from the caller's own account, never from the
        // request. BookRequest carries no libraryId, and giving it one would let
        // any librarian file stock into another library by changing a number.
        // Resolved before the ISBN check, which is now scoped to it.
        Library library = authenticatedUser(authenticatedUsername).getLibrary();

        ensureIsbnIsAvailable(request.getIsbn(), null, library.getId());

        Book book = new Book();
        book.setLibrary(library);
        applyRequestToBook(request, book, library.getId());

        // A brand new title has nothing on loan yet, so every copy is on the
        // shelf. Deriving it rather than accepting it means a book cannot be
        // created already claiming copies are out.
        book.setTotalCopies(request.getTotalCopies());
        book.setAvailableCopies(request.getTotalCopies());

        return toResponse(bookRepository.save(book));
    }

    /**
     * Overwrites an existing book's details with the values supplied.
     *
     * <p>The important detail is that we <b>load the existing row first</b> and
     * copy the six editable fields onto it, rather than saving a new object.
     * Saving a fresh object would be wrong: it carries no id, so JPA would
     * insert a duplicate row instead of updating this one. Copying field by
     * field also means the id can never be changed by the request.</p>
     *
     * <p>The ISBN is checked too, but with one important subtlety: a book is
     * allowed to keep the ISBN it already has. Only an ISBN belonging to a
     * <i>different</i> book is a conflict, which is why the id being updated is
     * passed to the check below.</p>
     *
     * <p>{@code @Transactional} matters more here than it looks. This method
     * reads both copy counts, works out how many are on loan from the
     * difference, and writes both back. Without a transaction around the read
     * and the write, an issue or return committing in between would leave that
     * difference stale and the recomputed availability quietly wrong - the very
     * rule this method exists to enforce. Inside one transaction, the version
     * check on {@link Book} turns that race into a refusal instead.</p>
     *
     * @throws BookNotFoundException  if no book has this id
     * @throws DuplicateIsbnException if the new ISBN belongs to another book
     */
    @Transactional
    public BookResponse updateBook(Long id, BookRequest request, String authenticatedUsername) {
        Long libraryId = libraryIdOf(authenticatedUsername);

        Book existingBook = findBookOrThrow(id, libraryId);
        ensureIsbnIsAvailable(request.getIsbn(), id, libraryId);

        applyRequestToBook(request, existingBook, libraryId);
        applyTotalCopies(existingBook, request.getTotalCopies());

        return toResponse(bookRepository.save(existingBook));
    }

    /**
     * Removes a book from the library.
     *
     * <p>We check with {@code existsById} before deleting. Calling
     * {@code deleteById} on an id that is not there does nothing at all and
     * reports no problem, which would leave the caller thinking a delete
     * succeeded when there was never anything to delete. The explicit check
     * turns that silence into a clear error.</p>
     *
     * @throws BookNotFoundException if no book has this id
     */
    public void deleteBook(Long id, String authenticatedUsername) {
        Book book = findBookOrThrow(id, libraryIdOf(authenticatedUsername));

        bookRepository.delete(book);
    }

    // ------------------------------------------------------------------
    //  Helpers: the entity <-> DTO mapping, kept private to this class
    // ------------------------------------------------------------------

    /**
     * Builds the WHERE clause from whichever filters were supplied.
     *
     * <p>Starts from {@code always()} and narrows with {@code and(...)}, so the
     * three cases - neither filter, one, or both - are the same three lines
     * rather than a branch per combination.</p>
     *
     * <p>A blank or whitespace-only keyword is treated as <b>no keyword</b>
     * rather than as a search for spaces, which would match nothing and look
     * like a bug to the caller. It is trimmed first, so " java " and "java"
     * behave identically.</p>
     *
     * <p>An unknown categoryId is rejected up front. Filtering on it would
     * otherwise return an empty page, which reads as "this category has no
     * books" when the truth is "there is no such category" - two very different
     * answers.</p>
     */
    private Specification<Book> buildFilter(String keyword, Long categoryId, Long libraryId) {
        // The tenant predicate is the starting point, not an optional extra, so
        // every filter combination below narrows an already-scoped result set.
        Specification<Book> specification = BookSpecifications.belongsToLibrary(libraryId);

        if (categoryId != null) {
            if (categoryRepository.findByIdAndLibraryId(categoryId, libraryId).isEmpty()) {
                throw new CategoryNotFoundException(categoryId);
            }
            specification = specification.and(BookSpecifications.hasCategory(categoryId));
        }

        if (hasKeyword(keyword)) {
            specification = specification.and(BookSpecifications.matchesKeyword(keyword.trim()));
        }

        return specification;
    }

    /** A keyword only counts if it holds something other than whitespace. */
    private boolean hasKeyword(String keyword) {
        return keyword != null && !keyword.trim().isEmpty();
    }

    /**
     * Rejects page and size values that cannot be honoured.
     *
     * <p>Checked here rather than left to {@code PageRequest.of}, which throws
     * a raw {@link IllegalArgumentException} for a negative page - that would
     * surface as a 500 and tell the caller nothing useful. Each message names
     * the offending value and the rule it broke.</p>
     */
    private void validatePagination(int page, int size) {
        if (page < 0) {
            throw new InvalidPaginationException("Page must be 0 or greater, but was " + page);
        }
        if (size < 1) {
            throw new InvalidPaginationException("Size must be at least 1, but was " + size);
        }
        if (size > MAX_PAGE_SIZE) {
            throw new InvalidPaginationException(
                    "Size must not exceed " + MAX_PAGE_SIZE + ", but was " + size);
        }
    }

    /**
     * Turns the requested field and direction into a safe {@link Sort}.
     *
     * <p>The field is looked up in {@link #SORTABLE_FIELDS} rather than trusted,
     * so only the six supported names get through and the property actually used
     * is the one this class defines.</p>
     *
     * <p>Direction is compared case-insensitively, so ASC, Asc and asc all mean
     * the same thing. It is checked explicitly instead of calling
     * {@code Sort.Direction.fromString}, which throws a raw
     * IllegalArgumentException that would surface as a 500.</p>
     *
     * <p>When sorting by anything other than id, id ascending is appended as a
     * tie-breaker. Without it, ordering by a column with repeated values - two
     * books with the same author, or the same copy count - leaves the order of
     * the tied rows up to the database, and a client paging through could see
     * one book twice while missing another. The tie-breaker makes every page
     * boundary deterministic.</p>
     */
    private Sort resolveSort(String sortBy, String direction) {
        String property = SORTABLE_FIELDS.get(sortBy);
        if (property == null) {
            throw new InvalidSortException("Unsupported sort field: " + sortBy
                    + ". Allowed fields are: "
                    + String.join(", ", new TreeSet<>(SORTABLE_FIELDS.keySet())));
        }

        Sort.Direction sortDirection;
        if ("asc".equalsIgnoreCase(direction)) {
            sortDirection = Sort.Direction.ASC;
        } else if ("desc".equalsIgnoreCase(direction)) {
            sortDirection = Sort.Direction.DESC;
        } else {
            throw new InvalidSortException("Unsupported sort direction: " + direction
                    + ". Allowed directions are: asc, desc");
        }

        Sort sort = Sort.by(sortDirection, property);

        return "id".equals(property) ? sort : sort.and(Sort.by(Sort.Direction.ASC, "id"));
    }

    /**
     * Fetches the entity or fails loudly.
     *
     * <p>Private and returning {@link Book} rather than a DTO, because the
     * update path needs the real managed entity to modify. Writing the
     * "missing book" rule here means it exists in exactly one place, and every
     * public method that needs a book by id goes through it.</p>
     */
    private Book findBookOrThrow(Long id, Long libraryId) {
        // A book in another library and a book that never existed both arrive
        // here as an empty Optional and leave as the same 404. Splitting them
        // into 404 and 403 would turn the id into a directory of a neighbour's
        // stock: every 403 would confirm a real book.
        return bookRepository.findByIdAndLibraryId(id, libraryId)
                .orElseThrow(() -> new BookNotFoundException(id));
    }

    /**
     * Copies the six editable fields from a request onto an entity.
     *
     * <p>Shared by create and update, which is why it takes the target book as
     * an argument: create passes a new Book, update passes the row it just
     * loaded. Note what is <i>not</i> copied - the id - which is exactly the
     * protection a DTO is meant to give.</p>
     */
    private void applyRequestToBook(BookRequest request, Book book, Long libraryId) {
        book.setTitle(request.getTitle());
        book.setAuthor(request.getAuthor());
        book.setIsbn(request.getIsbn());
        book.setCategory(resolveCategory(request.getCategoryId(), libraryId));
    }

    /**
     * Re-stocks a book to a new total while preserving what is on loan.
     *
     * <p>The count of issued copies is the one fact this method must not lose:
     * it is the difference between what the library owns and what is on the
     * shelf, and it corresponds to physical books in readers' hands. So the
     * new availability is derived from it rather than taken from the request:</p>
     *
     * <pre>
     *     issued    = oldTotal - oldAvailable
     *     available = newTotal - issued
     * </pre>
     *
     * <p>Adding copies puts the new ones straight on the shelf; removing copies
     * takes them off the shelf, never out of a reader's hands. A total below the
     * issued count is refused, because there is no honest availability that
     * satisfies it - the alternative would be a negative shelf count, or
     * silently forgetting a loan.</p>
     *
     * @param book           the book being edited, still holding its stored counts
     * @param newTotalCopies the requested total
     * @throws InvalidCopyCountException if the new total is below the issued count
     */
    private void applyTotalCopies(Book book, int newTotalCopies) {
        int issuedCopies = book.getTotalCopies() - book.getAvailableCopies();

        if (newTotalCopies < issuedCopies) {
            throw new InvalidCopyCountException(
                    "Total copies cannot be less than the number currently issued: requested "
                            + newTotalCopies + " but " + issuedCopies + " are on loan");
        }

        book.setTotalCopies(newTotalCopies);
        book.setAvailableCopies(newTotalCopies - issuedCopies);
    }

    /**
     * Converts a stored entity into the object the API sends back.
     *
     * <p>Uses the all-arguments constructor Lombok generated on BookResponse,
     * so the fields are supplied in the order they are declared there:
     * id first, then the six details.</p>
     */
    /**
     * Refuses an ISBN that is already spoken for.
     *
     * <p>Uses the {@code findByIsbn} query already declared on the repository.
     * The {@code filter} is what makes one method serve both create and update:
     * a book found under this ISBN is only a problem if it is a
     * <b>different</b> book from the one being saved.</p>
     *
     * <ul>
     *   <li>Create passes {@code null} as currentBookId, so any match at all is
     *       a clash.</li>
     *   <li>Update passes the id being edited, so a book keeping its own ISBN
     *       is filtered out and allowed through.</li>
     * </ul>
     *
     * <p>Checking here rather than letting the database's unique constraint
     * fire means no failed INSERT is ever attempted, and the client gets a
     * readable message instead of an SQL error.</p>
     */
    private void ensureIsbnIsAvailable(String isbn, Long currentBookId, Long libraryId) {
        // Scoped to the caller's library: two libraries stocking the same title
        // is the normal case, and the global check this replaces refused the
        // second one outright. The filter still excludes the row being edited,
        // so a book may keep its own ISBN on update.
        bookRepository.findByIsbnAndLibraryId(isbn, libraryId)
                .filter(bookWithSameIsbn -> !bookWithSameIsbn.getId().equals(currentBookId))
                .ifPresent(bookWithSameIsbn -> {
                    throw new DuplicateIsbnException(isbn);
                });
    }

    /**
     * Turns the {@code categoryId} from a request into a real Category row.
     *
     * <p>A null id is not an error - a book without a category is allowed, and
     * this simply returns null so the {@code category_id} column stays empty.</p>
     *
     * <p>An id that matches nothing <i>is</i> an error. Saving the book with no
     * category would quietly discard what the client asked for, so this raises
     * {@link CategoryNotFoundException} instead, which the exception handler
     * turns into a 404.</p>
     */
    private Category resolveCategory(Long categoryId, Long libraryId) {
        if (categoryId == null) {
            return null;
        }

        // Scoped, so a book can never be filed under another library's shelf.
        // A category that exists but belongs elsewhere is reported exactly as a
        // category that does not exist, disclosing nothing about the neighbour.
        return categoryRepository.findByIdAndLibraryId(categoryId, libraryId)
                .orElseThrow(() -> new CategoryNotFoundException(categoryId));
    }

    /**
     * The account behind the authenticated name.
     *
     * <p>Every library-sensitive method in this class reaches its tenant through
     * here, so the library is always derived from the caller the server
     * authenticated and never from anything the request carried.</p>
     *
     * @param authenticatedUsername the caller's login name
     * @return the caller's account
     * @throws UserNotFoundException if the authenticated name matches no account
     */
    private User authenticatedUser(String authenticatedUsername) {
        return userRepository.findByUsername(authenticatedUsername)
                .orElseThrow(() -> new UserNotFoundException(authenticatedUsername));
    }

    /**
     * The id of the library the caller belongs to.
     *
     * <p>Reading a lazy proxy's identifier does not initialise it, so the read
     * paths need no transaction; {@link #createBook} is the exception, because
     * it associates the proxy itself with a new row.</p>
     *
     * @param authenticatedUsername the caller's login name
     * @return that account's library id
     */
    private Long libraryIdOf(String authenticatedUsername) {
        return authenticatedUser(authenticatedUsername).getLibrary().getId();
    }

    private BookResponse toResponse(Book book) {
        return new BookResponse(
                book.getId(),
                book.getTitle(),
                book.getAuthor(),
                book.getIsbn(),
                book.getCategory() != null ? book.getCategory().getId() : null,
                book.getCategory() != null ? book.getCategory().getName() : null,
                book.getTotalCopies(),
                book.getAvailableCopies());
    }
}
