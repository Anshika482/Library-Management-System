package com.library.lms.service;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.library.lms.dto.PagedResponse;
import com.library.lms.dto.TransactionResponse;
import com.library.lms.entity.Book;
import com.library.lms.entity.Library;
import com.library.lms.entity.Role;
import com.library.lms.entity.Transaction;
import com.library.lms.entity.TransactionStatus;
import com.library.lms.entity.User;
import com.library.lms.exception.BookNotAvailableException;
import com.library.lms.exception.BookNotFoundException;
import com.library.lms.exception.InvalidDueDateException;
import com.library.lms.exception.InvalidPaginationException;
import com.library.lms.exception.InvalidSortException;
import com.library.lms.exception.MemberNotEligibleException;
import com.library.lms.exception.ReturnBookNotAllowedException;
import com.library.lms.exception.TransactionAccessDeniedException;
import com.library.lms.exception.TransactionNotFoundException;
import com.library.lms.exception.UserNotFoundException;
import com.library.lms.repository.BookRepository;
import com.library.lms.repository.TransactionRepository;
import com.library.lms.repository.UserRepository;

/**
 * Business logic for borrowing and returning books.
 *
 * <p>Issuing, returning and read-only lookups are implemented, with overdue
 * detection and fines. <b>Whether a loan is overdue is worked out, not
 * stored.</b> An open loan is reported OVERDUE, with the fine it has run up so
 * far, from the day after its due date - on every read, from the due date and
 * today's date - so it is right the moment a due date passes, without anything
 * having to run first, and no read ever writes. Only {@link #returnBook} stores
 * a fine: the amount owed on the day the book came back, which is then what is
 * reported however much later the loan is looked at. The rule, the rate and the
 * date all come from {@link OverduePolicy}.</p>
 *
 * <p>Constructor injection with final fields, exactly as {@link BookService}
 * and {@link CategoryService} do.</p>
 *
 * <p>Every method here is scoped to one library - the caller's own, resolved
 * from the authenticated login name by {@link #authenticatedUser}. No method
 * accepts a library from the client, and no transaction lookup in this class is
 * unscoped, so a caller can neither read nor alter another library's loans.
 * Role and ownership rules still apply <i>within</i> that library and are
 * unchanged: the tenant boundary sits underneath them, not instead of them.</p>
 */
@Service
public class TransactionService {

    /**
     * The largest page a client may ask for.
     *
     * <p>The same ceiling {@code BookService} applies, and for the same reason:
     * without one, {@code ?size=1000000} would pull a whole library's lending
     * history into memory in a single request - exactly what pagination exists
     * to prevent. Kept identical to the book value deliberately, so the API has
     * one answer to "how large may a page be?" rather than two.</p>
     */
    private static final int MAX_PAGE_SIZE = 50;

    /**
     * The only fields a client may sort a loan list by, mapped to the property
     * name used in the query.
     *
     * <p>This map is the security boundary for sorting. The value handed to
     * {@code Sort.by} is taken from the <b>right-hand side</b> - a constant
     * written here - never from the request, so nothing a client types can
     * reach the query as a property path. An unrecognised name produces no
     * entry and the request is rejected.</p>
     *
     * <p>Deliberately excluded: {@code book}, {@code user} and {@code library}.
     * All three are associations rather than plain columns, so sorting by them
     * needs a join. {@code fineAmount} is excluded too: an open loan's fine is
     * worked out when it is read rather than stored, so sorting by the column
     * would place every overdue loan as if it owed nothing.</p>
     *
     * <p>{@code status} sorts by the stored state, which for an open loan is
     * ISSUED whether or not it is past due. A status list holds one status
     * only, so this affects just the order of a loan history.</p>
     */
    private static final Map<String, String> SORTABLE_FIELDS = Map.of(
            "id", "id",
            "issueDate", "issueDate",
            "dueDate", "dueDate",
            "returnDate", "returnDate",
            "status", "status");

    private final TransactionRepository transactionRepository;

    private final BookRepository bookRepository;

    private final UserRepository userRepository;

    private final OverduePolicy overduePolicy;

    public TransactionService(TransactionRepository transactionRepository,
                              BookRepository bookRepository,
                              UserRepository userRepository,
                              OverduePolicy overduePolicy) {
        this.transactionRepository = transactionRepository;
        this.bookRepository = bookRepository;
        this.userRepository = userRepository;
        this.overduePolicy = overduePolicy;
    }

    /**
     * Issues one copy of a book to a user.
     *
     * <p>Two things happen together: a Transaction row is written, and the
     * book's available count drops by one. {@code @Transactional} is what makes
     * "together" true - if either statement fails, the whole method is rolled
     * back. Without it a crash between the two writes would leave a book
     * recorded as borrowed with its count untouched, or a count decremented
     * with no record of who has the copy. Neither error is self-correcting, and
     * both are the kind that surface weeks later as an unexplained shortfall.</p>
     *
     * <p>The annotation is on the method rather than the class deliberately.
     * Read-only lookups added here later have no need of a transaction, and a
     * class-level annotation would silently wrap them too.</p>
     *
     * <p>Order matters as well: both the book and the user are resolved, and
     * availability is checked, <b>before</b> anything is written. A rejected
     * issue therefore leaves no partial state at all - no row, no decrement.</p>
     *
     * <p>Returns a {@link TransactionResponse} rather than the entity, matching
     * {@link BookService} and {@link CategoryService}: the entity stops at this
     * layer. That is not merely tidiness - a Transaction holds a User, and a
     * User holds a password hash, so handing the entity to a controller would
     * put credential material one Jackson call away from an HTTP response.</p>
     *
     * <p>The borrower is named by {@code username}, which callers must take from
     * the authenticated principal and never from request input. The parameter is
     * a login name rather than an id for exactly that reason: an id would invite
     * a caller to supply one, and issuing a book to an account chosen by the
     * requester is the flaw this signature exists to prevent.</p>
     *
     * <p>That same name decides the library. The book is looked up within the
     * caller's own tenant, so a book id from another library is refused as
     * missing rather than borrowed, and the loan that is written records the
     * owning library explicitly.</p>
     *
     * @param bookId   the book to issue
     * @param username the authenticated borrower's login name
     * @param dueDate  when it must come back, decided by the caller
     * @return the saved loan, carrying the id the database generated
     * @throws BookNotFoundException     if the caller's library has no book with
     *                                   this id - including when the book exists
     *                                   in another library
     * @throws UserNotFoundException     if no account has this login name
     * @throws BookNotAvailableException if every copy is already on loan
     * @throws InvalidDueDateException   if the due date is missing, or falls
     *                                   before the date the server stamps on
     *                                   the loan
     */
    @Transactional
    public TransactionResponse issueBook(Long bookId, Long memberId, String username, LocalDate dueDate) {
        // Stamped once, here, and used for both the check below and the stored
        // row. Asking for the date twice would compare one day and save another,
        // which is the whole failure this guard exists to prevent. It comes from
        // the clock overdue loans are judged by, so a loan and its fine never
        // disagree about what day it is.
        LocalDate issueDate = overduePolicy.today();

        // Checked before any lookup, so a request that cannot produce a valid
        // loan never touches the database. The DTO's @FutureOrPresent still
        // runs at the HTTP boundary and is unchanged; this covers what that
        // annotation cannot - a caller reaching the service directly, and the
        // gap between validating a request and executing it.
        if (dueDate == null) {
            throw new InvalidDueDateException();
        }
        if (dueDate.isBefore(issueDate)) {
            throw new InvalidDueDateException(dueDate, issueDate);
        }

        // Resolved from the authenticated name, never from anything the caller
        // sent. The account is read again rather than trusted from the token,
        // so a deleted or renamed user cannot still borrow on an old session.
        // It is now resolved first as well: the caller's library is what scopes
        // the book lookup below, so it must be known before any book is read.
        User user = authenticatedUser(username);
        Library library = user.getLibrary();

        // Scoped to the caller's own library, which closes the worst of the
        // cross-library holes. Unscoped, a librarian could name any book id in
        // the database and the decrement below would come out of another
        // library's stock - a write into a tenant they have no business
        // touching. A book belonging elsewhere now fails exactly as one that
        // was never created, so the refusal reveals nothing either way.
        Book book = bookRepository.findByIdAndLibraryId(bookId, library.getId())
                .orElseThrow(() -> new BookNotFoundException(bookId));

        // The borrower, resolved the same way the book is: by id within the
        // caller's own library. A member of another library is therefore not
        // found at all, which is the same answer an id belonging to nobody
        // gets - the refusal says nothing about whether the account exists
        // somewhere else.
        User borrower = userRepository.findByIdAndLibraryId(memberId, library.getId())
                .orElseThrow(() -> new UserNotFoundException(memberId));

        // Three separate reasons an account cannot take a book home, all
        // answered identically: it is staff rather than a member, it has been
        // disabled, or it has been locked. Distinguishing them here would make
        // this endpoint a way to read another account's status.
        if (borrower.getRole() != Role.ROLE_MEMBER
                || !borrower.isEnabled()
                || !borrower.isAccountNonLocked()) {
            throw new MemberNotEligibleException();
        }

        Integer availableCopies = book.getAvailableCopies();

        // A null check as well as a zero check: the column is NOT NULL, but the
        // field is an Integer, and reading it as an int would throw an opaque
        // NullPointerException rather than the business error the caller expects.
        // Testing "not greater than zero" rather than "equals zero" also means a
        // count that is somehow already negative is refused instead of driven
        // further down.
        if (availableCopies == null || availableCopies <= 0) {
            throw new BookNotAvailableException(bookId, book.getTitle());
        }

        book.setAvailableCopies(availableCopies - 1);
        bookRepository.save(book);

        Transaction transaction = new Transaction();
        transaction.setBook(book);

        // The member, never the member of staff who processed it. The loan has
        // to name whoever actually has the book.
        transaction.setUser(borrower);

        // The owning library comes from the caller's own account, never from
        // the request. Because the book and the borrower were both fetched
        // within that same library, the three agree by construction rather
        // than by a check afterwards:
        // transaction.library == borrower.library == book.library.
        transaction.setLibrary(library);

        transaction.setIssueDate(issueDate);
        transaction.setDueDate(dueDate);

        // Set explicitly rather than left to default, because these two nulls
        // carry meaning: the book is still out, and no fine has been assessed.
        transaction.setReturnDate(null);
        transaction.setFineAmount(null);

        transaction.setStatus(TransactionStatus.ISSUED);

        return toResponse(transactionRepository.save(transaction), issueDate);
    }

    /**
     * Takes a book back and puts the copy back on the shelf.
     *
     * <p>The mirror of {@link #issueBook}: one Transaction row is updated and
     * the book's available count goes up by one, and {@code @Transactional}
     * makes those a single unit. A crash between them would either lose the
     * copy - recorded as returned but never added back - or add a phantom copy
     * with the loan still open. Neither self-corrects.</p>
     *
     * <p>Everything is validated before anything is written, so a rejected
     * return leaves no partial state: no date, no status change, no increment.</p>
     *
     * <p><b>The fine is fixed here.</b> It is the days between the due date and
     * the return date times the daily rate - zero for a book back on or before
     * its due date - and it is stored. That amount is final: every later read
     * reports it rather than recalculating, so a loan returned long ago does not
     * go on accruing, and a later change of rate does not rewrite what was owed.
     * A zero is written rather than left null, because a fine has now been
     * worked out and found to be nothing.</p>
     *
     * <p><b>A fine cannot be charged twice.</b> Only an open loan can be
     * returned, so a second return is refused before anything is calculated,
     * and two returns racing each other collide on the row's version: the second
     * fails instead of closing the loan again.</p>
     *
     * <p>The loan is fetched within the caller's own library. Before that this
     * method took no authenticated name at all, so any staff member could close
     * any loan in the database and push a copy back onto another library's
     * shelf. Scoping the lookup refuses that before the book is read, which is
     * what leaves the other library's counts untouched.</p>
     *
     * <p>No ownership rule is added here. Who may return a book is still
     * decided by the role rules in the security configuration, exactly as
     * before; the only new restriction is the tenant boundary.</p>
     *
     * @param transactionId         the loan being closed
     * @param authenticatedUsername the caller's login name, which the caller
     *                              must take from the authenticated principal
     * @return the updated loan
     * @throws UserNotFoundException          if the authenticated name matches
     *                                        no account
     * @throws TransactionNotFoundException   if the caller's library has no loan
     *                                        with this id - including when the
     *                                        loan exists in another library
     * @throws ReturnBookNotAllowedException  if it is not in a returnable state
     */
    @Transactional
    public TransactionResponse returnBook(Long transactionId, String authenticatedUsername) {
        Long libraryId = authenticatedUser(authenticatedUsername).getLibrary().getId();

        Transaction transaction = transactionRepository
                .findByIdAndLibraryId(transactionId, libraryId)
                .orElseThrow(() -> new TransactionNotFoundException(transactionId));

        // Only an open loan can be closed: ISSUED, or OVERDUE should a row have
        // been stored that way - an overdue book is the one that most needs to
        // come back. Naming the open states rather than testing for "not
        // RETURNED" means a future status such as LOST is refused by default
        // instead of being silently treated as returnable.
        if (!overduePolicy.isOpen(transaction.getStatus())) {
            throw new ReturnBookNotAllowedException(transactionId, transaction.getStatus());
        }

        // The book comes from the relationship already loaded with the
        // transaction - no second lookup is needed.
        Book book = transaction.getBook();
        if (book == null) {
            throw new ReturnBookNotAllowedException(
                    "Transaction has no book associated with it (id " + transactionId + ")");
        }

        Integer availableCopies = book.getAvailableCopies();
        Integer totalCopies = book.getTotalCopies();

        if (availableCopies == null) {
            throw new ReturnBookNotAllowedException(
                    "Book has no available copy count recorded, so a copy cannot be restored (book id "
                            + book.getId() + ")");
        }

        // Refuse to put back a copy the library does not own. Without this a
        // repeated or spurious return would push availableCopies past
        // totalCopies, and a count that exceeds the real stock is corruption
        // that no later operation would notice.
        if (totalCopies != null && availableCopies + 1 > totalCopies) {
            throw new ReturnBookNotAllowedException(
                    "Returning would leave more copies available than the library owns: book id "
                            + book.getId() + " already has " + availableCopies + " of " + totalCopies);
        }

        book.setAvailableCopies(availableCopies + 1);
        bookRepository.save(book);

        // One date for both: the day the book came back is the day its fine is
        // counted to.
        LocalDate returnDate = overduePolicy.today();

        transaction.setReturnDate(returnDate);
        transaction.setFineAmount(overduePolicy.fineFor(transaction.getDueDate(), returnDate).doubleValue());
        transaction.setStatus(TransactionStatus.RETURNED);

        return toResponse(transactionRepository.save(transaction), returnDate);
    }

    /**
     * Fetches one loan by its id, if the caller is entitled to see it.
     *
     * <p>Read-only, so no {@code @Transactional}: a single query needs no
     * transaction boundary, and adding one would imply this method writes.</p>
     *
     * <p>ADMIN and LIBRARIAN may read any loan <b>in their own library</b> and
     * get the usual 404 for an id that is not there. A MEMBER may read only a
     * loan whose borrower is their own account, decided by comparing database
     * ids. Neither role can reach across libraries: the query is scoped before
     * either rule is applied.</p>
     *
     * <p><b>A member is told the same thing either way.</b> An id that does not
     * exist and an id belonging to someone else both raise
     * {@link TransactionAccessDeniedException}. Splitting them into 404 and 403
     * would make the endpoint enumerable: every 403 would confirm a real loan,
     * and counting them would reveal how much the library lends. The row is read
     * before the decision because ownership cannot be known without it, but
     * nothing about a row they may not see reaches the caller.</p>
     *
     * @param transactionId         the loan wanted
     * @param authenticatedUsername the caller's login name, which the caller must
     *                              take from the authenticated principal
     * @return the loan, mapped to a response
     * @throws UserNotFoundException            if the authenticated name matches
     *                                          no account
     * @throws TransactionNotFoundException     if staff ask for an id their
     *                                          library does not hold - including
     *                                          one that exists elsewhere
     * @throws TransactionAccessDeniedException if a member asks for a loan that
     *                                          is not theirs, or one that does
     *                                          not exist
     */
    public TransactionResponse getTransactionById(Long transactionId, String authenticatedUsername) {
        User authenticatedUser = authenticatedUser(authenticatedUsername);

        // Scoped first, so everything below reasons only about loans the
        // caller's library actually holds. A loan from another library is not
        // filtered out after being read - it is never returned by the query,
        // which is why staff see it as an ordinary missing id and a member sees
        // the same refusal they get for any loan that is not theirs.
        Optional<Transaction> transaction = transactionRepository
                .findByIdAndLibraryId(transactionId, authenticatedUser.getLibrary().getId());

        if (maySeeAnyUsersActivity(authenticatedUser)) {
            return toResponse(transaction
                    .orElseThrow(() -> new TransactionNotFoundException(transactionId)), overduePolicy.today());
        }

        // A member gets one answer to two different questions. Returning 404 for
        // an id that does not exist and 403 for one that belongs to somebody else
        // would turn this endpoint into a directory: walk the ids, and every 403
        // marks a real loan. Filtering to loans they own and refusing everything
        // else makes the two cases indistinguishable from outside.
        return toResponse(transaction
                .filter(loan -> loan.getUser() != null
                        && Objects.equals(loan.getUser().getId(), authenticatedUser.getId()))
                .orElseThrow(TransactionAccessDeniedException::new), overduePolicy.today());
    }

    /**
     * Every loan recorded against one book, current and historical.
     *
     * <p>An unknown book id yields an empty page rather than a 404. That
     * matches {@code getBooksByCategory} in {@link BookService}: asking "what
     * has happened to this book?" and getting nothing back is a valid answer,
     * and checking the book exists first would cost an extra query to change a
     * 200 into a 404 without telling the caller anything more useful.</p>
     *
     * <p>A book belonging to another library gets that same empty page, which
     * is why no book lookup happens here at all. Loading the book to decide
     * whether it exists would create the very distinction this endpoint must
     * not offer: an empty result for a book nobody borrowed and a 404 for a
     * book in the next library would let a caller map another tenant's
     * catalogue one id at a time. Paging changed the shape of that empty answer
     * and nothing about what it reveals.</p>
     *
     * <p>Paged on the same terms as {@link #getTransactionsByStatus}, down to
     * the shared {@link #validatePagination} and {@link #resolveSort}: a
     * popular title's lifetime history grows without limit, and library scoping
     * bounds whose loans come back but not how many.</p>
     *
     * @param bookId                the book whose history is wanted
     * @param page                  which page, zero-based
     * @param size                  how many loans per page, at most
     *                              {@value #MAX_PAGE_SIZE}
     * @param sortBy                one of {@link #SORTABLE_FIELDS}
     * @param direction             asc or desc
     * @param authenticatedUsername the caller's login name, which the caller
     *                              must take from the authenticated principal
     * @return one page of that book's loans within the caller's library
     * @throws UserNotFoundException      if the authenticated name matches no
     *                                    account
     * @throws InvalidPaginationException if page or size is out of range
     * @throws InvalidSortException       if the sort field or direction is not
     *                                    supported
     */
    public PagedResponse<TransactionResponse> getTransactionsByBook(Long bookId,
                                                                    int page, int size,
                                                                    String sortBy, String direction,
                                                                    String authenticatedUsername) {
        // Unchanged: the library comes from the caller's own account, and is
        // resolved before anything is read.
        Long libraryId = authenticatedUser(authenticatedUsername).getLibrary().getId();

        validatePagination(page, size);

        Pageable pageable = PageRequest.of(page, size, resolveSort(sortBy, direction));

        Page<Transaction> transactions =
                transactionRepository.findByBookIdAndLibraryId(bookId, libraryId, pageable);

        return toPagedResponse(transactions, overduePolicy.today());
    }

    /**
     * Loads the account behind an authenticated login name.
     *
     * <p>The one place this class turns a name into a user, so every method
     * resolves the caller - and therefore the caller's library - the same way.
     * Named and written exactly as the matching helper in {@link BookService}
     * and {@link CategoryService}, because a second way of answering "who is
     * calling?" is a second thing that can drift.</p>
     *
     * <p>The name must come from the authenticated principal. A missing account
     * raises the project's existing {@link UserNotFoundException} rather than
     * returning a null that would later read as "no library".</p>
     *
     * @param authenticatedUsername the caller's login name
     * @return the account it names
     * @throws UserNotFoundException if no account has this login name
     */
    private User authenticatedUser(String authenticatedUsername) {
        return userRepository.findByUsername(authenticatedUsername)
                .orElseThrow(() -> new UserNotFoundException(authenticatedUsername));
    }

    /**
     * Whether this account may read other people's borrowing activity.
     *
     * <p>Extracted so the two ownership checks in this class ask the same
     * question of the same enum. Two copies of a rule like this drift: one gains
     * a new role and the other quietly keeps refusing, or worse, quietly keeps
     * allowing.</p>
     *
     * <p>Comparing the enum rather than a string means a renamed constant is a
     * compile error instead of a silent mismatch. A null role, which the NOT
     * NULL column should make impossible, falls through as false - the safe
     * direction.</p>
     *
     * @param user the account asking
     * @return true for ADMIN and LIBRARIAN, false for everyone else
     */
    private boolean maySeeAnyUsersActivity(User user) {
        return user.getRole() == Role.ROLE_ADMIN || user.getRole() == Role.ROLE_LIBRARIAN;
    }

    /**
     * Every loan belonging to one user, if the caller is entitled to see it.
     *
     * <p>The rule lives here rather than in the controller on purpose. A path
     * variable is not proof of identity - a member can edit the id in the URL as
     * easily as reading it - so the check has to sit where the data is fetched.
     * Putting it in the controller would leave the method reachable, unguarded,
     * by anything else that later calls the service.</p>
     *
     * <p>ADMIN and LIBRARIAN may read any history <b>within their own
     * library</b>; a MEMBER may read only their own. Ownership is decided by
     * comparing the requested id with the id of the account loaded from
     * {@code authenticatedUsername}, so the caller supplies a name and the
     * server supplies the id the name maps to.</p>
     *
     * <p>Paged on the same terms as the other two list reads, reusing the same
     * {@link #validatePagination} and {@link #resolveSort}. Note the order of
     * the two refusals: <b>ownership is decided first</b>, so a member asking
     * for somebody else's history is told 403 whatever page or size they sent.
     * Validating pagination first would answer 400 instead, which quietly turns
     * an authorization failure into a request-format complaint and hands the
     * caller a way to distinguish "not allowed" from "badly asked".</p>
     *
     * @param userId                the account whose history is wanted
     * @param page                  which page, zero-based
     * @param size                  how many loans per page, at most
     *                              {@value #MAX_PAGE_SIZE}
     * @param sortBy                one of {@link #SORTABLE_FIELDS}
     * @param direction             asc or desc
     * @param authenticatedUsername the caller's login name, which the caller
     *                              must take from the authenticated principal
     * @return one page of that user's loans within the caller's library
     * @throws UserNotFoundException             if the authenticated name matches
     *                                           no account
     * @throws TransactionAccessDeniedException  if a member asks for someone
     *                                           else's history
     * @throws InvalidPaginationException        if page or size is out of range
     * @throws InvalidSortException              if the sort field or direction
     *                                           is not supported
     */
    public PagedResponse<TransactionResponse> getTransactionsByUser(Long userId,
                                                                    int page, int size,
                                                                    String sortBy, String direction,
                                                                    String authenticatedUsername) {
        User authenticatedUser = authenticatedUser(authenticatedUsername);

        // Staff see any history; a member sees only their own. The comparison is
        // between the requested id and the id on the account the server just
        // loaded - never the name in the URL, and never the name against an id.
        //
        // This stays the first decision after resolving the caller. Pagination
        // is validated below it, not above: an unauthorized member must get the
        // same 403 whether their page and size were sensible or nonsense.
        if (!maySeeAnyUsersActivity(authenticatedUser)
                && !Objects.equals(authenticatedUser.getId(), userId)) {
            // Thrown before the rows are fetched, so a refused request never
            // reads the data it was refused. Objects.equals rather than == so
            // two equal Long ids above the cache range still compare equal.
            throw new TransactionAccessDeniedException();
        }

        Long libraryId = authenticatedUser.getLibrary().getId();

        validatePagination(page, size);

        Pageable pageable = PageRequest.of(page, size, resolveSort(sortBy, direction));

        // Scoped to the caller's library as well as checked for ownership. The
        // two rules are independent: ownership decides whose history a member
        // may ask for, the library decides which rows exist to be asked about.
        // A user id from another library matches nothing and yields the same
        // empty page as an account that has never borrowed anything.
        Page<Transaction> transactions =
                transactionRepository.findByUserIdAndLibraryId(userId, libraryId, pageable);

        return toPagedResponse(transactions, overduePolicy.today());
    }

    /**
     * Every loan currently in one state.
     *
     * <p>Takes the {@link TransactionStatus} enum rather than a String, so an
     * unrecognised value is rejected by Spring's own conversion at the edge and
     * never reaches a query.</p>
     *
     * <p><b>ISSUED and OVERDUE are decided by the due date</b>, exactly as a
     * single loan is reported. OVERDUE is every open loan whose due date is
     * before today, each with its fine so far; ISSUED is every open loan not yet
     * past due. A loan moves from one list to the other the day after its due
     * date without anything being written. RETURNED is the stored state, with
     * the fine fixed on return.</p>
     *
     * <p>Scoped to the caller's library, which this endpoint needed more than
     * any other: unscoped, one request for ISSUED returned every open loan held
     * by every library in the system.</p>
     *
     * <p>Paged, unlike the other two list methods. Scoping the query to one
     * library bounds whose loans come back but not how many, and this endpoint
     * is the widest of the three - "every open loan" grows with the library.
     * The page, size, sort field and direction all follow the convention
     * {@code BookService} already established, so the two paginated endpoints
     * behave identically from outside.</p>
     *
     * @param status                the state to match
     * @param page                  which page, zero-based
     * @param size                  how many loans per page, at most
     *                              {@value #MAX_PAGE_SIZE}
     * @param sortBy                one of {@link #SORTABLE_FIELDS}
     * @param direction             asc or desc
     * @param authenticatedUsername the caller's login name, which the caller
     *                              must take from the authenticated principal
     * @return one page of that state's loans within the caller's library
     * @throws UserNotFoundException       if the authenticated name matches no
     *                                     account
     * @throws InvalidPaginationException  if page or size is out of range
     * @throws InvalidSortException        if the sort field or direction is not
     *                                     supported
     */
    public PagedResponse<TransactionResponse> getTransactionsByStatus(TransactionStatus status,
                                                                      int page, int size,
                                                                      String sortBy, String direction,
                                                                      String authenticatedUsername) {
        // Unchanged from before pagination: the library comes from the caller's
        // own account, and is resolved before anything is read.
        Long libraryId = authenticatedUser(authenticatedUsername).getLibrary().getId();

        validatePagination(page, size);

        Pageable pageable = PageRequest.of(page, size, resolveSort(sortBy, direction));

        // One date for the query and for the mapping, so a loan is never
        // selected as overdue and then reported as ISSUED - or the reverse - by
        // a request that happens to run across midnight.
        LocalDate today = overduePolicy.today();

        // ISSUED and OVERDUE are both open loans, told apart by today's date
        // rather than by the stored status; RETURNED is exactly what is stored.
        // A switch over the enum, so a status added later does not compile here
        // until somebody decides which rows it means.
        Page<Transaction> transactions = switch (status) {
            case ISSUED -> transactionRepository.findByStatusInAndDueDateGreaterThanEqualAndLibraryId(
                    OverduePolicy.OPEN_STATUSES, today, libraryId, pageable);
            case OVERDUE -> transactionRepository.findByStatusInAndDueDateBeforeAndLibraryId(
                    OverduePolicy.OPEN_STATUSES, today, libraryId, pageable);
            case RETURNED -> transactionRepository.findByStatusAndLibraryId(status, libraryId, pageable);
        };

        return toPagedResponse(transactions, today);
    }

    /**
     * Turns a page of stored loans into the page the API sends back.
     *
     * <p>Shared by the two paged reads so the wrapper cannot drift between
     * them: the totals always describe the whole matching set, never the slice,
     * and the entities always stop at this layer - a Transaction holds a User,
     * and a User holds a password hash.</p>
     */
    private PagedResponse<TransactionResponse> toPagedResponse(Page<Transaction> transactions, LocalDate today) {
        List<TransactionResponse> content = transactions.getContent()
                .stream()
                .map(transaction -> toResponse(transaction, today))
                .toList();

        return new PagedResponse<>(
                content,
                transactions.getNumber(),
                transactions.getSize(),
                transactions.getTotalElements(),
                transactions.getTotalPages());
    }

    /**
     * Refuses a page or size the API will not serve.
     *
     * <p>Written to match {@code BookService.validatePagination} exactly,
     * including the messages, so a caller who has met one paginated endpoint
     * already knows what this one will say.</p>
     *
     * @throws InvalidPaginationException for a negative page, a size below one,
     *                                    or a size above {@value #MAX_PAGE_SIZE}
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
     * Turns a requested sort field and direction into a {@link Sort}, or
     * refuses it.
     *
     * <p>The field is looked up in {@link #SORTABLE_FIELDS} rather than passed
     * through, so an unknown name is a clean 400 instead of a
     * PropertyReferenceException surfacing as a 500 that names the entity's
     * internals.</p>
     *
     * <p>A secondary sort by id is appended unless id is already the sort
     * field. Without it, rows sharing a value - every loan issued on the same
     * day, say - have no defined order between them, and the same row could
     * appear on two consecutive pages or on neither.</p>
     *
     * @throws InvalidSortException for an unknown field or direction
     */
    private Sort resolveSort(String sortBy, String direction) {
        String property = SORTABLE_FIELDS.get(sortBy);
        if (property == null) {
            // The rejected value is not repeated back: it is raw query-string
            // input of any length and content. The allowed list is what makes
            // the failure actionable, and it is fixed text.
            throw new InvalidSortException("Unsupported sort field. Allowed fields are: "
                    + String.join(", ", new TreeSet<>(SORTABLE_FIELDS.keySet())));
        }

        Sort.Direction sortDirection;
        if ("asc".equalsIgnoreCase(direction)) {
            sortDirection = Sort.Direction.ASC;
        } else if ("desc".equalsIgnoreCase(direction)) {
            sortDirection = Sort.Direction.DESC;
        } else {
            throw new InvalidSortException(
                    "Unsupported sort direction. Allowed directions are: asc, desc");
        }

        Sort sort = Sort.by(sortDirection, property);

        return "id".equals(property) ? sort : sort.and(Sort.by(Sort.Direction.ASC, "id"));
    }

    /**
     * Converts a stored loan into the object the API sends back.
     *
     * <p>The book and the user are flattened to their ids. Copying them across
     * as objects would publish the whole Book, and worse the whole User -
     * password hash, email and role included - in a response that is supposed
     * to describe a loan. A client needing those details asks the relevant
     * endpoint for them.</p>
     *
     * <p>Null-safe on both associations. Both columns are NOT NULL so neither
     * should ever be absent, but a mapper that throws a NullPointerException on
     * unexpected data is harder to diagnose than one that reports the null.</p>
     *
     * <p><b>An open loan is described as it stands on {@code today}</b>: OVERDUE
     * with its fine so far once its due date has passed, ISSUED with nothing owed
     * until then. The caller passes the date, so every loan in one response - and
     * the query that selected them - is judged by the same day. A returned loan
     * is described exactly as stored, fine included.</p>
     */
    private TransactionResponse toResponse(Transaction transaction, LocalDate today) {
        TransactionStatus status = transaction.getStatus();
        Double fineAmount = transaction.getFineAmount();

        // Worked out here and never written, so reading a loan any number of
        // times cannot change it.
        if (overduePolicy.isOpen(status)) {
            boolean overdue = overduePolicy.isOverdue(transaction.getDueDate(), today);

            status = overdue ? TransactionStatus.OVERDUE : TransactionStatus.ISSUED;
            fineAmount = overdue ? overduePolicy.fineFor(transaction.getDueDate(), today).doubleValue() : null;
        }

        return new TransactionResponse(
                transaction.getId(),
                transaction.getBook() != null ? transaction.getBook().getId() : null,
                transaction.getUser() != null ? transaction.getUser().getId() : null,
                transaction.getIssueDate(),
                transaction.getDueDate(),
                transaction.getReturnDate(),
                fineAmount,
                status);
    }
}
