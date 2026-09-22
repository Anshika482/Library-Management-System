package com.library.lms.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import com.library.lms.dto.TransactionResponse;
import com.library.lms.entity.Book;
import com.library.lms.entity.Library;
import com.library.lms.entity.Role;
import com.library.lms.entity.Transaction;
import com.library.lms.entity.TransactionStatus;
import com.library.lms.entity.User;
import com.library.lms.exception.TransactionAccessDeniedException;
import com.library.lms.exception.TransactionNotFoundException;
import com.library.lms.exception.UserNotFoundException;
import com.library.lms.repository.BookRepository;
import com.library.lms.repository.TransactionRepository;
import com.library.lms.repository.UserRepository;

/**
 * Guards ownership of a single loan read by id.
 *
 * <p>Every collaborator is mocked, so no Spring context starts and no row is
 * read or written.</p>
 *
 * <p>The subtlest requirement here is not that a member is refused - it is that
 * a member cannot tell <i>why</i>. A loan that belongs to somebody else and an
 * id that was never issued must produce the same refusal, or the endpoint
 * becomes a directory that can be walked one id at a time.</p>
 */
@ExtendWith(MockitoExtension.class)
class TransactionServiceGetByIdTest {

    private static final Long TRANSACTION_ID = 500L;

    private static final Long OWN_USER_ID = 42L;

    private static final Long OTHER_USER_ID = 99L;

    /** The caller's own library. Every fixture below belongs to it. */
    private static final Long LIBRARY_ID = 1L;

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private BookRepository bookRepository;

    @Mock
    private UserRepository userRepository;

    /** The real overdue rules at 1.00 a day, on the system clock the fixtures' dates are built from. */
    @Spy
    private OverduePolicy overduePolicy = new OverduePolicy("1.00");

    /** The audit boundary: what it was told is asserted where it matters, and ignored elsewhere. */
    @Mock
    private AuditService auditService;

    @InjectMocks
    private TransactionService transactionService;

    private static Library library(Long id) {
        Library library = new Library();
        library.setId(id);
        return library;
    }

    private static User account(Long id, String username, Role role) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        user.setRole(role);
        user.setLibrary(library(LIBRARY_ID));
        return user;
    }

    private static Transaction loanOwnedBy(Long ownerId) {
        Book book = new Book();
        book.setId(5L);

        User owner = new User();
        owner.setId(ownerId);

        Transaction transaction = new Transaction();
        transaction.setId(TRANSACTION_ID);
        transaction.setBook(book);
        transaction.setUser(owner);
        transaction.setLibrary(library(LIBRARY_ID));
        transaction.setIssueDate(LocalDate.now());
        transaction.setDueDate(LocalDate.now().plusDays(14));
        transaction.setStatus(TransactionStatus.ISSUED);
        return transaction;
    }

    private void authenticatedAs(String username, Long id, Role role) {
        when(userRepository.findByUsername(username)).thenReturn(Optional.of(account(id, username, role)));
    }

    @Test
    void adminMayReadAnotherUsersTransaction() {
        authenticatedAs("an-admin", 1L, Role.ROLE_ADMIN);
        when(transactionRepository.findByIdAndLibraryId(TRANSACTION_ID, LIBRARY_ID)).thenReturn(Optional.of(loanOwnedBy(OTHER_USER_ID)));

        TransactionResponse response = transactionService.getTransactionById(TRANSACTION_ID, "an-admin");

        assertThat(response.getId()).isEqualTo(TRANSACTION_ID);
        assertThat(response.getUserId()).isEqualTo(OTHER_USER_ID);
    }

    @Test
    void librarianMayReadAnotherUsersTransaction() {
        authenticatedAs("a-librarian", 2L, Role.ROLE_LIBRARIAN);
        when(transactionRepository.findByIdAndLibraryId(TRANSACTION_ID, LIBRARY_ID)).thenReturn(Optional.of(loanOwnedBy(OTHER_USER_ID)));

        TransactionResponse response = transactionService.getTransactionById(TRANSACTION_ID, "a-librarian");

        assertThat(response.getUserId()).isEqualTo(OTHER_USER_ID);
    }

    @Test
    void memberMayReadTheirOwnTransaction() {
        authenticatedAs("a-member", OWN_USER_ID, Role.ROLE_MEMBER);
        when(transactionRepository.findByIdAndLibraryId(TRANSACTION_ID, LIBRARY_ID)).thenReturn(Optional.of(loanOwnedBy(OWN_USER_ID)));

        TransactionResponse response = transactionService.getTransactionById(TRANSACTION_ID, "a-member");

        assertThat(response.getId()).isEqualTo(TRANSACTION_ID);
        assertThat(response.getUserId()).isEqualTo(OWN_USER_ID);
    }

    @Test
    void memberMayNotReadAnotherUsersTransaction() {
        authenticatedAs("a-member", OWN_USER_ID, Role.ROLE_MEMBER);
        when(transactionRepository.findByIdAndLibraryId(TRANSACTION_ID, LIBRARY_ID)).thenReturn(Optional.of(loanOwnedBy(OTHER_USER_ID)));

        assertThatThrownBy(() -> transactionService.getTransactionById(TRANSACTION_ID, "a-member"))
                .isInstanceOf(TransactionAccessDeniedException.class)
                .hasMessage("Access denied");
    }

    @Test
    void refusalCarriesNoDetailOfTheTransactionItRefused() {
        authenticatedAs("a-member", OWN_USER_ID, Role.ROLE_MEMBER);
        when(transactionRepository.findByIdAndLibraryId(TRANSACTION_ID, LIBRARY_ID)).thenReturn(Optional.of(loanOwnedBy(OTHER_USER_ID)));

        Throwable refusal = catchThrowable(
                () -> transactionService.getTransactionById(TRANSACTION_ID, "a-member"));

        assertThat(refusal.getMessage())
                .isEqualTo("Access denied")
                .doesNotContain(String.valueOf(TRANSACTION_ID))
                .doesNotContain(String.valueOf(OTHER_USER_ID))
                .doesNotContain(String.valueOf(OWN_USER_ID))
                .doesNotContain("a-member");
    }

    @Test
    void memberCannotTellAForeignTransactionFromOneThatDoesNotExist() {
        // The whole anti-enumeration requirement in one assertion: both answers
        // must be the same object type and the same message, so walking ids
        // reveals nothing about which of them are real.
        authenticatedAs("a-member", OWN_USER_ID, Role.ROLE_MEMBER);
        when(transactionRepository.findByIdAndLibraryId(TRANSACTION_ID, LIBRARY_ID)).thenReturn(Optional.of(loanOwnedBy(OTHER_USER_ID)));
        when(transactionRepository.findByIdAndLibraryId(12345L, LIBRARY_ID)).thenReturn(Optional.empty());

        Throwable foreign = catchThrowable(
                () -> transactionService.getTransactionById(TRANSACTION_ID, "a-member"));
        Throwable missing = catchThrowable(
                () -> transactionService.getTransactionById(12345L, "a-member"));

        assertThat(foreign).isInstanceOf(TransactionAccessDeniedException.class);
        assertThat(missing).isInstanceOf(TransactionAccessDeniedException.class);
        assertThat(missing.getClass()).isEqualTo(foreign.getClass());
        assertThat(missing.getMessage()).isEqualTo(foreign.getMessage());
    }

    @Test
    void staffStillGetTheOrdinaryNotFoundForAMissingId() {
        // The anti-enumeration collapse applies to members only; staff keep the
        // more useful answer.
        authenticatedAs("an-admin", 1L, Role.ROLE_ADMIN);
        when(transactionRepository.findByIdAndLibraryId(12345L, LIBRARY_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> transactionService.getTransactionById(12345L, "an-admin"))
                .isInstanceOf(TransactionNotFoundException.class);
    }

    @Test
    void ownershipIsDecidedByDatabaseIdNotByUsername() {
        // A member whose username is the digits of the owning user's id. A
        // name-against-id comparison would let this through.
        authenticatedAs(String.valueOf(OTHER_USER_ID), OWN_USER_ID, Role.ROLE_MEMBER);
        when(transactionRepository.findByIdAndLibraryId(TRANSACTION_ID, LIBRARY_ID)).thenReturn(Optional.of(loanOwnedBy(OTHER_USER_ID)));

        assertThatThrownBy(() -> transactionService.getTransactionById(TRANSACTION_ID,
                String.valueOf(OTHER_USER_ID)))
                .isInstanceOf(TransactionAccessDeniedException.class);
    }

    @Test
    void equalIdsAboveTheLongCacheRangeStillCountAsOwnership() {
        // Long values over 127 are not interned, so a reference comparison would
        // wrongly refuse a member reading their own loan.
        Long largeId = 100_000L;
        authenticatedAs("a-member", largeId, Role.ROLE_MEMBER);
        when(transactionRepository.findByIdAndLibraryId(TRANSACTION_ID, LIBRARY_ID))
                .thenReturn(Optional.of(loanOwnedBy(Long.valueOf(100_000L))));

        TransactionResponse response = transactionService.getTransactionById(TRANSACTION_ID, "a-member");

        assertThat(response.getUserId()).isEqualTo(largeId);
    }

    @Test
    void aLoanWithNoBorrowerIsRefusedToAMember() {
        authenticatedAs("a-member", OWN_USER_ID, Role.ROLE_MEMBER);
        Transaction orphan = loanOwnedBy(OWN_USER_ID);
        orphan.setUser(null);
        when(transactionRepository.findByIdAndLibraryId(TRANSACTION_ID, LIBRARY_ID)).thenReturn(Optional.of(orphan));

        assertThatThrownBy(() -> transactionService.getTransactionById(TRANSACTION_ID, "a-member"))
                .isInstanceOf(TransactionAccessDeniedException.class);
    }

    @Test
    void unresolvableAuthenticatedNameIsRejectedRatherThanAllowed() {
        when(userRepository.findByUsername("ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> transactionService.getTransactionById(TRANSACTION_ID, "ghost"))
                .isInstanceOf(UserNotFoundException.class);
    }
}
