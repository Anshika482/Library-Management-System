package com.library.lms.service;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.library.lms.dto.TransactionResponse;
import com.library.lms.entity.Book;
import com.library.lms.entity.Role;
import com.library.lms.entity.Transaction;
import com.library.lms.entity.TransactionStatus;
import com.library.lms.entity.User;
import com.library.lms.exception.BookNotAvailableException;
import com.library.lms.exception.BookNotFoundException;
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
 * <p>Issuing, returning and read-only lookups are implemented. Fine
 * calculation and overdue detection are not - a returned book leaves
 * {@code fineAmount} exactly as it was, and no method here changes a status
 * except {@link #returnBook}.</p>
 *
 * <p>Constructor injection with final fields, exactly as {@link BookService}
 * and {@link CategoryService} do.</p>
 */
@Service
public class TransactionService {

    private final TransactionRepository transactionRepository;

    private final BookRepository bookRepository;

    private final UserRepository userRepository;

    public TransactionService(TransactionRepository transactionRepository,
                              BookRepository bookRepository,
                              UserRepository userRepository) {
        this.transactionRepository = transactionRepository;
        this.bookRepository = bookRepository;
        this.userRepository = userRepository;
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
     * @param bookId   the book to issue
     * @param username the authenticated borrower's login name
     * @param dueDate  when it must come back, decided by the caller
     * @return the saved loan, carrying the id the database generated
     * @throws BookNotFoundException     if no book has this id
     * @throws UserNotFoundException     if no account has this login name
     * @throws BookNotAvailableException if every copy is already on loan
     */
    @Transactional
    public TransactionResponse issueBook(Long bookId, String username, LocalDate dueDate) {
        Book book = bookRepository.findById(bookId)
                .orElseThrow(() -> new BookNotFoundException(bookId));

        // Resolved from the authenticated name, never from anything the caller
        // sent. The account is read again rather than trusted from the token,
        // so a deleted or renamed user cannot still borrow on an old session.
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new UserNotFoundException(username));

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
        transaction.setUser(user);
        transaction.setIssueDate(LocalDate.now());
        transaction.setDueDate(dueDate);

        // Set explicitly rather than left to default, because these two nulls
        // carry meaning: the book is still out, and no fine has been assessed.
        transaction.setReturnDate(null);
        transaction.setFineAmount(null);

        transaction.setStatus(TransactionStatus.ISSUED);

        return toResponse(transactionRepository.save(transaction));
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
     * <p>{@code fineAmount} is deliberately left untouched. Overdue charges are
     * a later step, and quietly writing a zero here would be indistinguishable
     * from a fine that had been calculated and found to be nil.</p>
     *
     * @param transactionId the loan being closed
     * @return the updated loan
     * @throws TransactionNotFoundException   if no transaction has this id
     * @throws ReturnBookNotAllowedException  if it is not in a returnable state
     */
    @Transactional
    public TransactionResponse returnBook(Long transactionId) {
        Transaction transaction = transactionRepository.findById(transactionId)
                .orElseThrow(() -> new TransactionNotFoundException(transactionId));

        // Only an open loan can be closed. Checking for ISSUED rather than
        // "not RETURNED" means a future status such as LOST is refused by
        // default instead of being silently treated as returnable.
        if (transaction.getStatus() != TransactionStatus.ISSUED) {
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

        transaction.setReturnDate(LocalDate.now());
        transaction.setStatus(TransactionStatus.RETURNED);

        return toResponse(transactionRepository.save(transaction));
    }

    /**
     * Fetches one loan by its id, if the caller is entitled to see it.
     *
     * <p>Read-only, so no {@code @Transactional}: a single query needs no
     * transaction boundary, and adding one would imply this method writes.</p>
     *
     * <p>ADMIN and LIBRARIAN may read any loan and get the usual 404 for an id
     * that is not there. A MEMBER may read only a loan whose borrower is their
     * own account, decided by comparing database ids.</p>
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
     * @throws TransactionNotFoundException     if staff ask for an id that is not
     *                                          there
     * @throws TransactionAccessDeniedException if a member asks for a loan that
     *                                          is not theirs, or one that does
     *                                          not exist
     */
    public TransactionResponse getTransactionById(Long transactionId, String authenticatedUsername) {
        User authenticatedUser = userRepository.findByUsername(authenticatedUsername)
                .orElseThrow(() -> new UserNotFoundException(authenticatedUsername));

        Optional<Transaction> transaction = transactionRepository.findById(transactionId);

        if (maySeeAnyUsersActivity(authenticatedUser)) {
            return toResponse(transaction
                    .orElseThrow(() -> new TransactionNotFoundException(transactionId)));
        }

        // A member gets one answer to two different questions. Returning 404 for
        // an id that does not exist and 403 for one that belongs to somebody else
        // would turn this endpoint into a directory: walk the ids, and every 403
        // marks a real loan. Filtering to loans they own and refusing everything
        // else makes the two cases indistinguishable from outside.
        return toResponse(transaction
                .filter(loan -> loan.getUser() != null
                        && Objects.equals(loan.getUser().getId(), authenticatedUser.getId()))
                .orElseThrow(TransactionAccessDeniedException::new));
    }

    /**
     * Every loan recorded against one book, current and historical.
     *
     * <p>An unknown book id yields an empty list rather than a 404. That
     * matches {@code getBooksByCategory} in {@link BookService}: asking "what
     * has happened to this book?" and getting nothing back is a valid answer,
     * and checking the book exists first would cost an extra query to change a
     * 200 into a 404 without telling the caller anything more useful.</p>
     */
    public List<TransactionResponse> getTransactionsByBook(Long bookId) {
        return transactionRepository.findByBookId(bookId)
                .stream()
                .map(this::toResponse)
                .toList();
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
     * <p>ADMIN and LIBRARIAN may read any history; a MEMBER may read only their
     * own. Ownership is decided by comparing the requested id with the id of the
     * account loaded from {@code authenticatedUsername}, so the caller supplies
     * a name and the server supplies the id the name maps to.</p>
     *
     * @param userId                the account whose history is wanted
     * @param authenticatedUsername the caller's login name, which the caller
     *                              must take from the authenticated principal
     * @return that user's loans, oldest first as the repository returns them
     * @throws UserNotFoundException             if the authenticated name matches
     *                                           no account
     * @throws TransactionAccessDeniedException  if a member asks for someone
     *                                           else's history
     */
    public List<TransactionResponse> getTransactionsByUser(Long userId, String authenticatedUsername) {
        User authenticatedUser = userRepository.findByUsername(authenticatedUsername)
                .orElseThrow(() -> new UserNotFoundException(authenticatedUsername));

        // Staff see any history; a member sees only their own. The comparison is
        // between the requested id and the id on the account the server just
        // loaded - never the name in the URL, and never the name against an id.
        if (!maySeeAnyUsersActivity(authenticatedUser)
                && !Objects.equals(authenticatedUser.getId(), userId)) {
            // Thrown before the rows are fetched, so a refused request never
            // reads the data it was refused. Objects.equals rather than == so
            // two equal Long ids above the cache range still compare equal.
            throw new TransactionAccessDeniedException();
        }

        return transactionRepository.findByUserId(userId)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * Every loan currently in one state.
     *
     * <p>Takes the {@link TransactionStatus} enum rather than a String, so an
     * unrecognised value is rejected by Spring's own conversion at the edge and
     * never reaches a query.</p>
     *
     * <p>Note this reports the status <b>as stored</b>. Nothing here recomputes
     * whether an ISSUED loan is now past its due date - overdue detection is a
     * later step, so asking for OVERDUE returns only rows already marked that
     * way.</p>
     */
    public List<TransactionResponse> getTransactionsByStatus(TransactionStatus status) {
        return transactionRepository.findByStatus(status)
                .stream()
                .map(this::toResponse)
                .toList();
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
     */
    private TransactionResponse toResponse(Transaction transaction) {
        return new TransactionResponse(
                transaction.getId(),
                transaction.getBook() != null ? transaction.getBook().getId() : null,
                transaction.getUser() != null ? transaction.getUser().getId() : null,
                transaction.getIssueDate(),
                transaction.getDueDate(),
                transaction.getReturnDate(),
                transaction.getFineAmount(),
                transaction.getStatus());
    }
}
