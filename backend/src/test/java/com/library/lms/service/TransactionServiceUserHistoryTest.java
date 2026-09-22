package com.library.lms.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import com.library.lms.dto.PagedResponse;
import com.library.lms.dto.TransactionResponse;
import com.library.lms.entity.Book;
import com.library.lms.entity.Library;
import com.library.lms.entity.Role;
import com.library.lms.entity.Transaction;
import com.library.lms.entity.TransactionStatus;
import com.library.lms.entity.User;
import com.library.lms.exception.TransactionAccessDeniedException;
import com.library.lms.exception.UserNotFoundException;
import com.library.lms.repository.BookRepository;
import com.library.lms.repository.TransactionRepository;
import com.library.lms.repository.UserRepository;

/**
 * Guards ownership of a member's borrowing history.
 *
 * <p>Every collaborator is mocked, so no Spring context starts and no row is
 * read or written. The interesting assertions are the negative ones: that a
 * member asking for somebody else's history is refused, and refused
 * <i>before</i> the repository is touched, so the rows they were not allowed to
 * see are never fetched at all.</p>
 */
@ExtendWith(MockitoExtension.class)
class TransactionServiceUserHistoryTest {

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

    private static Transaction loan(Long userId) {
        Book book = new Book();
        book.setId(5L);

        User user = new User();
        user.setId(userId);

        Transaction transaction = new Transaction();
        transaction.setId(1001L);
        transaction.setBook(book);
        transaction.setUser(user);
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
    void adminMayReadAnotherUsersHistory() {
        authenticatedAs("an-admin", 1L, Role.ROLE_ADMIN);
        when(transactionRepository.findByUserIdAndLibraryId(
                eq(OTHER_USER_ID), eq(LIBRARY_ID), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(loan(OTHER_USER_ID))));

        PagedResponse<TransactionResponse> history = transactionService.getTransactionsByUser(
                OTHER_USER_ID, 0, 10, "id", "asc", "an-admin");

        assertThat(history.getContent()).hasSize(1);
        assertThat(history.getContent().get(0).getUserId()).isEqualTo(OTHER_USER_ID);
    }

    @Test
    void librarianMayReadAnotherUsersHistory() {
        authenticatedAs("a-librarian", 2L, Role.ROLE_LIBRARIAN);
        when(transactionRepository.findByUserIdAndLibraryId(
                eq(OTHER_USER_ID), eq(LIBRARY_ID), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(loan(OTHER_USER_ID))));

        PagedResponse<TransactionResponse> history = transactionService.getTransactionsByUser(
                OTHER_USER_ID, 0, 10, "id", "asc", "a-librarian");

        assertThat(history.getContent()).hasSize(1);
        assertThat(history.getContent().get(0).getUserId()).isEqualTo(OTHER_USER_ID);
    }

    @Test
    void memberMayReadTheirOwnHistory() {
        authenticatedAs("a-member", OWN_USER_ID, Role.ROLE_MEMBER);
        when(transactionRepository.findByUserIdAndLibraryId(
                eq(OWN_USER_ID), eq(LIBRARY_ID), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(loan(OWN_USER_ID))));

        PagedResponse<TransactionResponse> history = transactionService.getTransactionsByUser(
                OWN_USER_ID, 0, 10, "id", "asc", "a-member");

        assertThat(history.getContent()).hasSize(1);
        assertThat(history.getContent().get(0).getUserId()).isEqualTo(OWN_USER_ID);
    }

    @Test
    void memberMayNotReadAnotherUsersHistory() {
        authenticatedAs("a-member", OWN_USER_ID, Role.ROLE_MEMBER);

        assertThatThrownBy(() -> transactionService.getTransactionsByUser(OTHER_USER_ID, 0, 10, "id", "asc", "a-member"))
                .isInstanceOf(TransactionAccessDeniedException.class)
                .hasMessage("Access denied");
    }

    @Test
    void refusedRequestNeverReadsTheRowsItWasRefused() {
        authenticatedAs("a-member", OWN_USER_ID, Role.ROLE_MEMBER);

        assertThatThrownBy(() -> transactionService.getTransactionsByUser(OTHER_USER_ID, 0, 10, "id", "asc", "a-member"))
                .isInstanceOf(TransactionAccessDeniedException.class);

        verify(transactionRepository, never())
                .findByUserIdAndLibraryId(anyLong(), anyLong(), any(Pageable.class));
    }

    @Test
    void refusalRevealsNothingAboutTheRequestedAccount() {
        authenticatedAs("a-member", OWN_USER_ID, Role.ROLE_MEMBER);

        Throwable refusal = org.assertj.core.api.Assertions.catchThrowable(
                () -> transactionService.getTransactionsByUser(OTHER_USER_ID, 0, 10, "id", "asc", "a-member"));

        assertThat(refusal).isInstanceOf(TransactionAccessDeniedException.class);
        assertThat(refusal.getMessage())
                .isEqualTo("Access denied")
                .doesNotContain(String.valueOf(OTHER_USER_ID))
                .doesNotContain(String.valueOf(OWN_USER_ID))
                .doesNotContain("a-member");

        // The requested account is never looked up at all, so the answer cannot
        // differ between an id that exists and one that does not.
        verify(userRepository, never()).findById(anyLong());
    }

    @Test
    void ownershipIsDecidedByDatabaseIdNotByUsername() {
        // A member whose username is the digits of somebody else's id. If the
        // check ever compared the name against the requested id, this would slip
        // through; comparing against the loaded account's own id refuses it.
        authenticatedAs(String.valueOf(OTHER_USER_ID), OWN_USER_ID, Role.ROLE_MEMBER);

        assertThatThrownBy(() -> transactionService.getTransactionsByUser(
                OTHER_USER_ID, 0, 10, "id", "asc", String.valueOf(OTHER_USER_ID)))
                .isInstanceOf(TransactionAccessDeniedException.class);
    }

    @Test
    void equalIdsAboveTheLongCacheRangeStillCountAsOwnership() {
        // Long values over 127 are not interned, so a reference comparison would
        // wrongly refuse a member reading their own history.
        Long largeId = 100_000L;
        authenticatedAs("a-member", largeId, Role.ROLE_MEMBER);
        when(transactionRepository.findByUserIdAndLibraryId(
                eq(Long.valueOf(100_000L)), eq(LIBRARY_ID), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(loan(largeId))));

        PagedResponse<TransactionResponse> history =
                transactionService.getTransactionsByUser(Long.valueOf(100_000L), 0, 10, "id", "asc", "a-member");

        assertThat(history.getContent()).hasSize(1);
    }

    @Test
    void unresolvableAuthenticatedNameIsRejectedRatherThanAllowed() {
        when(userRepository.findByUsername("ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> transactionService.getTransactionsByUser(OWN_USER_ID, 0, 10, "id", "asc", "ghost"))
                .isInstanceOf(UserNotFoundException.class);

        verify(transactionRepository, never())
                .findByUserIdAndLibraryId(anyLong(), anyLong(), any(Pageable.class));
    }
}
