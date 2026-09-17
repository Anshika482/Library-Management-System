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
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import com.library.lms.dto.PagedResponse;
import com.library.lms.dto.TransactionResponse;
import com.library.lms.entity.Book;
import com.library.lms.entity.Library;
import com.library.lms.entity.Role;
import com.library.lms.entity.Transaction;
import com.library.lms.entity.TransactionStatus;
import com.library.lms.entity.User;
import com.library.lms.exception.InvalidPaginationException;
import com.library.lms.exception.InvalidSortException;
import com.library.lms.exception.TransactionAccessDeniedException;
import com.library.lms.repository.BookRepository;
import com.library.lms.repository.TransactionRepository;
import com.library.lms.repository.UserRepository;

/**
 * Guards the paging, sorting, bounds and authorization order of the user-history
 * endpoint.
 *
 * <p>This is the last of the three list reads to be paged, and the only one with
 * an ownership rule of its own - the other two are staff-only at the filter
 * chain. That makes the <b>order</b> of the two refusals the interesting part,
 * and several tests here exist for nothing else: an unauthorized member must be
 * told 403 whatever page and size they sent. Validating pagination first would
 * answer 400 instead, which turns an authorization failure into a complaint
 * about request format and hands the caller a way to tell "not allowed" apart
 * from "badly asked".</p>
 *
 * <p>The tenant assertions are made on the repository <i>call</i> rather than on
 * the answer, because a test that only checked returned content would pass just
 * as happily against an unscoped query returning a neighbouring library's rows.</p>
 *
 * <p>Every collaborator is mocked, so no Spring context starts and no row is read
 * or written.</p>
 */
@ExtendWith(MockitoExtension.class)
class TransactionServiceUserHistoryPaginationTest {

    private static final Long LIBRARY_ID = 1L;

    private static final Long OTHER_LIBRARY_ID = 2L;

    private static final String MEMBER = "a-member";

    private static final String ADMIN = "an-admin";

    private static final String LIBRARIAN = "a-librarian";

    private static final Long OWN_USER_ID = 42L;

    private static final Long OTHER_USER_ID = 99L;

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private BookRepository bookRepository;

    @Mock
    private UserRepository userRepository;

    /** The real overdue rules at 1.00 a day, on the system clock the fixtures' dates are built from. */
    @Spy
    private OverduePolicy overduePolicy = new OverduePolicy("1.00");

    @InjectMocks
    private TransactionService transactionService;

    // ---------- fixtures ----------

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

    private static Transaction loan(long id, Long borrowerId) {
        Book book = new Book();
        book.setId(500L);
        book.setLibrary(library(LIBRARY_ID));

        User borrower = new User();
        borrower.setId(borrowerId);
        borrower.setLibrary(library(LIBRARY_ID));

        Transaction transaction = new Transaction();
        transaction.setId(id);
        transaction.setBook(book);
        transaction.setUser(borrower);
        transaction.setLibrary(library(LIBRARY_ID));
        transaction.setIssueDate(LocalDate.now().minusDays(15));
        transaction.setDueDate(LocalDate.now().minusDays(1));
        transaction.setStatus(TransactionStatus.ISSUED);
        return transaction;
    }

    private static List<Transaction> loans(int count, Long borrowerId) {
        return IntStream.rangeClosed(1, count).mapToObj(i -> loan(i, borrowerId)).toList();
    }

    private void callerIs(String username, Long id, Role role) {
        when(userRepository.findByUsername(username)).thenReturn(Optional.of(account(id, username, role)));
    }

    private void memberCaller() {
        callerIs(MEMBER, OWN_USER_ID, Role.ROLE_MEMBER);
    }

    private void repositoryReturns(Long userId, List<Transaction> content, Pageable pageable, long total) {
        when(transactionRepository.findByUserIdAndLibraryId(
                eq(userId), eq(LIBRARY_ID), any(Pageable.class)))
                .thenReturn(new PageImpl<>(content, pageable, total));
    }

    private Pageable capturedPageable(Long userId) {
        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(transactionRepository).findByUserIdAndLibraryId(
                eq(userId), eq(LIBRARY_ID), captor.capture());
        return captor.getValue();
    }

    private PagedResponse<TransactionResponse> ownHistory(int page, int size, String sortBy,
                                                          String direction) {
        return transactionService.getTransactionsByUser(
                OWN_USER_ID, page, size, sortBy, direction, MEMBER);
    }

    // ---------- A-C: paging ----------

    @Test
    void defaultPagingAsksForTheFirstPageOfTen() {
        memberCaller();
        repositoryReturns(OWN_USER_ID, loans(10, OWN_USER_ID), PageRequest.of(0, 10), 10);

        ownHistory(0, 10, "id", "asc");

        Pageable used = capturedPageable(OWN_USER_ID);
        assertThat(used.getPageNumber()).isZero();
        assertThat(used.getPageSize()).isEqualTo(10);
    }

    @Test
    void aCustomPageReachesTheQuery() {
        memberCaller();
        repositoryReturns(OWN_USER_ID, loans(10, OWN_USER_ID), PageRequest.of(4, 10), 90);

        PagedResponse<TransactionResponse> response = ownHistory(4, 10, "id", "asc");

        assertThat(capturedPageable(OWN_USER_ID).getPageNumber()).isEqualTo(4);
        assertThat(response.getPage()).isEqualTo(4);
    }

    @Test
    void aCustomSizeReachesTheQuery() {
        memberCaller();
        repositoryReturns(OWN_USER_ID, loans(3, OWN_USER_ID), PageRequest.of(0, 3), 12);

        PagedResponse<TransactionResponse> response = ownHistory(0, 3, "id", "asc");

        assertThat(capturedPageable(OWN_USER_ID).getPageSize()).isEqualTo(3);
        assertThat(response.getSize()).isEqualTo(3);
    }

    // ---------- D-G: bounds ----------

    @Test
    void theMaximumSizeOfFiftyIsAccepted() {
        memberCaller();
        repositoryReturns(OWN_USER_ID, loans(50, OWN_USER_ID), PageRequest.of(0, 50), 50);

        assertThat(ownHistory(0, 50, "id", "asc").getSize()).isEqualTo(50);
    }

    @Test
    void aSizeAboveFiftyIsRejected() {
        memberCaller();

        assertThatThrownBy(() -> ownHistory(0, 51, "id", "asc"))
                .isInstanceOf(InvalidPaginationException.class);
    }

    @Test
    void aNegativePageIsRejected() {
        memberCaller();

        assertThatThrownBy(() -> ownHistory(-1, 10, "id", "asc"))
                .isInstanceOf(InvalidPaginationException.class);
    }

    @Test
    void aSizeOfZeroOrLessIsRejected() {
        memberCaller();

        assertThatThrownBy(() -> ownHistory(0, 0, "id", "asc"))
                .isInstanceOf(InvalidPaginationException.class);
        assertThatThrownBy(() -> ownHistory(0, -3, "id", "asc"))
                .isInstanceOf(InvalidPaginationException.class);
    }

    // ---------- H-L: every whitelisted sort field ----------

    @Test
    void sortingByIdIsAccepted() {
        memberCaller();
        repositoryReturns(OWN_USER_ID, loans(2, OWN_USER_ID), PageRequest.of(0, 10), 2);

        ownHistory(0, 10, "id", "asc");

        assertThat(capturedPageable(OWN_USER_ID).getSort().getOrderFor("id")).isNotNull();
    }

    @Test
    void sortingByIssueDateIsAccepted() {
        memberCaller();
        repositoryReturns(OWN_USER_ID, loans(2, OWN_USER_ID), PageRequest.of(0, 10), 2);

        ownHistory(0, 10, "issueDate", "asc");

        assertThat(capturedPageable(OWN_USER_ID).getSort().getOrderFor("issueDate")).isNotNull();
    }

    @Test
    void sortingByDueDateIsAccepted() {
        memberCaller();
        repositoryReturns(OWN_USER_ID, loans(2, OWN_USER_ID), PageRequest.of(0, 10), 2);

        ownHistory(0, 10, "dueDate", "asc");

        assertThat(capturedPageable(OWN_USER_ID).getSort().getOrderFor("dueDate")).isNotNull();
    }

    @Test
    void sortingByReturnDateIsAccepted() {
        memberCaller();
        repositoryReturns(OWN_USER_ID, loans(2, OWN_USER_ID), PageRequest.of(0, 10), 2);

        ownHistory(0, 10, "returnDate", "asc");

        assertThat(capturedPageable(OWN_USER_ID).getSort().getOrderFor("returnDate")).isNotNull();
    }

    @Test
    void sortingByStatusIsAccepted() {
        memberCaller();
        repositoryReturns(OWN_USER_ID, loans(2, OWN_USER_ID), PageRequest.of(0, 10), 2);

        ownHistory(0, 10, "status", "asc");

        assertThat(capturedPageable(OWN_USER_ID).getSort().getOrderFor("status")).isNotNull();
    }

    // ---------- M, N: rejected sorts ----------

    @Test
    void anInvalidSortFieldIsRejected() {
        memberCaller();

        assertThatThrownBy(() -> ownHistory(0, 10, "fineAmount", "asc"))
                .isInstanceOf(InvalidSortException.class);
        assertThatThrownBy(() -> ownHistory(0, 10, "user.password", "asc"))
                .isInstanceOf(InvalidSortException.class);

        verify(transactionRepository, never())
                .findByUserIdAndLibraryId(anyLong(), anyLong(), any(Pageable.class));
    }

    @Test
    void anInvalidDirectionIsRejected() {
        memberCaller();

        assertThatThrownBy(() -> ownHistory(0, 10, "id", "downwards"))
                .isInstanceOf(InvalidSortException.class);
    }

    // ---------- O, P: stable ordering ----------

    @Test
    void aSecondaryIdSortIsAppendedWhenSortingByAnotherField() {
        memberCaller();
        repositoryReturns(OWN_USER_ID, loans(2, OWN_USER_ID), PageRequest.of(0, 10), 2);

        ownHistory(0, 10, "dueDate", "desc");

        Sort sort = capturedPageable(OWN_USER_ID).getSort();
        assertThat(sort.getOrderFor("dueDate").getDirection()).isEqualTo(Sort.Direction.DESC);
        Sort.Order tiebreaker = sort.getOrderFor("id");
        assertThat(tiebreaker).as("loans sharing a due date need a defined order").isNotNull();
        assertThat(tiebreaker.getDirection()).isEqualTo(Sort.Direction.ASC);
    }

    @Test
    void noRedundantSecondarySortWhenAlreadySortingById() {
        memberCaller();
        repositoryReturns(OWN_USER_ID, loans(2, OWN_USER_ID), PageRequest.of(0, 10), 2);

        ownHistory(0, 10, "id", "desc");

        Sort sort = capturedPageable(OWN_USER_ID).getSort();
        assertThat(sort).hasSize(1);
        assertThat(sort.getOrderFor("id").getDirection()).isEqualTo(Sort.Direction.DESC);
    }

    // ---------- Q, R: mapping and empty pages ----------

    @Test
    void thePagedResultIsMappedCorrectly() {
        memberCaller();
        repositoryReturns(OWN_USER_ID, loans(4, OWN_USER_ID), PageRequest.of(2, 4), 30);

        PagedResponse<TransactionResponse> response = ownHistory(2, 4, "id", "asc");

        assertThat(response.getContent()).hasSize(4);
        assertThat(response.getContent())
                .extracting(TransactionResponse::getId).containsExactly(1L, 2L, 3L, 4L);
        assertThat(response.getContent().get(0).getUserId()).isEqualTo(OWN_USER_ID);
        assertThat(response.getPage()).isEqualTo(2);
        assertThat(response.getSize()).isEqualTo(4);
        assertThat(response.getTotalElements()).isEqualTo(30);
        assertThat(response.getTotalPages()).isEqualTo(8);
    }

    @Test
    void anEmptyHistoryIsAnEmptyPageNotAnError() {
        memberCaller();
        repositoryReturns(OWN_USER_ID, List.of(), PageRequest.of(0, 10), 0);

        PagedResponse<TransactionResponse> response = ownHistory(0, 10, "id", "asc");

        assertThat(response.getContent()).isEmpty();
        assertThat(response.getTotalElements()).isZero();
        assertThat(response.getTotalPages()).isZero();
    }

    // ---------- S, T: the tenant predicate ----------

    @Test
    void theAuthenticatedUsersLibraryIdIsPassedToTheRepository() {
        memberCaller();
        repositoryReturns(OWN_USER_ID, loans(1, OWN_USER_ID), PageRequest.of(0, 10), 1);

        ownHistory(0, 10, "id", "asc");

        verify(transactionRepository).findByUserIdAndLibraryId(
                anyLong(), eq(LIBRARY_ID), any(Pageable.class));
        verify(transactionRepository, never()).findByUserIdAndLibraryId(
                anyLong(), eq(OTHER_LIBRARY_ID), any(Pageable.class));
    }

    @Test
    void theRepositoryReceivesTheRequestedUserIdAndTheCallersLibrary() {
        callerIs(ADMIN, 1L, Role.ROLE_ADMIN);
        repositoryReturns(OTHER_USER_ID, loans(1, OTHER_USER_ID), PageRequest.of(0, 10), 1);

        transactionService.getTransactionsByUser(OTHER_USER_ID, 0, 10, "id", "asc", ADMIN);

        // The requested id is the filter; the library is the caller's own.
        verify(transactionRepository).findByUserIdAndLibraryId(
                eq(OTHER_USER_ID), eq(LIBRARY_ID), any(Pageable.class));
    }

    // ---------- U-X: member ownership, and its precedence ----------

    @Test
    void aMemberMayReadTheirOwnHistory() {
        memberCaller();
        repositoryReturns(OWN_USER_ID, loans(2, OWN_USER_ID), PageRequest.of(0, 10), 2);

        PagedResponse<TransactionResponse> response = ownHistory(0, 10, "id", "asc");

        assertThat(response.getContent()).hasSize(2);
        assertThat(response.getContent().get(0).getUserId()).isEqualTo(OWN_USER_ID);
    }

    @Test
    void aMemberMayNotReadAnotherUsersHistory() {
        memberCaller();

        assertThatThrownBy(() -> transactionService.getTransactionsByUser(
                OTHER_USER_ID, 0, 10, "id", "asc", MEMBER))
                .isInstanceOf(TransactionAccessDeniedException.class)
                .hasMessage("Access denied");
    }

    @Test
    void anUnauthorizedMemberNeverTriggersATransactionQuery() {
        memberCaller();

        assertThatThrownBy(() -> transactionService.getTransactionsByUser(
                OTHER_USER_ID, 0, 10, "id", "asc", MEMBER))
                .isInstanceOf(TransactionAccessDeniedException.class);

        verify(transactionRepository, never())
                .findByUserIdAndLibraryId(anyLong(), anyLong(), any(Pageable.class));
    }

    @Test
    void anUnauthorizedMemberIsDeniedEvenWithInvalidPaginationParameters() {
        // The ordering requirement in one test: authorization is decided before
        // pagination is validated, so a bad size cannot convert a 403 into a
        // 400 - which would let a caller tell "not allowed" from "badly asked".
        memberCaller();

        assertThatThrownBy(() -> transactionService.getTransactionsByUser(
                OTHER_USER_ID, -5, 999, "nonsense", "sideways", MEMBER))
                .isInstanceOf(TransactionAccessDeniedException.class)
                .isNotInstanceOf(InvalidPaginationException.class)
                .isNotInstanceOf(InvalidSortException.class);

        verify(transactionRepository, never())
                .findByUserIdAndLibraryId(anyLong(), anyLong(), any(Pageable.class));
    }

    // ---------- Y-AB: staff access and its tenant limit ----------

    @Test
    void anAdminMayReadAnotherUserInTheSameLibrary() {
        callerIs(ADMIN, 1L, Role.ROLE_ADMIN);
        repositoryReturns(OTHER_USER_ID, loans(1, OTHER_USER_ID), PageRequest.of(0, 10), 1);

        PagedResponse<TransactionResponse> response = transactionService.getTransactionsByUser(
                OTHER_USER_ID, 0, 10, "id", "asc", ADMIN);

        assertThat(response.getContent()).hasSize(1);
        assertThat(response.getContent().get(0).getUserId()).isEqualTo(OTHER_USER_ID);
    }

    @Test
    void anAdminCannotObtainAnotherLibrarysTransactions() {
        // Being staff widens whose history may be asked for, never which
        // library, so the query is still bound to the admin's own tenant.
        callerIs(ADMIN, 1L, Role.ROLE_ADMIN);
        repositoryReturns(OTHER_USER_ID, List.of(), PageRequest.of(0, 10), 0);

        PagedResponse<TransactionResponse> response = transactionService.getTransactionsByUser(
                OTHER_USER_ID, 0, 10, "id", "asc", ADMIN);

        assertThat(response.getContent()).isEmpty();
        verify(transactionRepository).findByUserIdAndLibraryId(
                eq(OTHER_USER_ID), eq(LIBRARY_ID), any(Pageable.class));
        verify(transactionRepository, never()).findByUserIdAndLibraryId(
                anyLong(), eq(OTHER_LIBRARY_ID), any(Pageable.class));
    }

    @Test
    void aLibrarianMayReadAnotherUserInTheSameLibrary() {
        callerIs(LIBRARIAN, 2L, Role.ROLE_LIBRARIAN);
        repositoryReturns(OTHER_USER_ID, loans(1, OTHER_USER_ID), PageRequest.of(0, 10), 1);

        PagedResponse<TransactionResponse> response = transactionService.getTransactionsByUser(
                OTHER_USER_ID, 0, 10, "id", "asc", LIBRARIAN);

        assertThat(response.getContent()).hasSize(1);
    }

    @Test
    void aLibrarianIsAlsoBoundedByTheirOwnLibrary() {
        callerIs(LIBRARIAN, 2L, Role.ROLE_LIBRARIAN);
        repositoryReturns(OTHER_USER_ID, List.of(), PageRequest.of(0, 10), 0);

        transactionService.getTransactionsByUser(OTHER_USER_ID, 0, 10, "id", "asc", LIBRARIAN);

        verify(transactionRepository).findByUserIdAndLibraryId(
                eq(OTHER_USER_ID), eq(LIBRARY_ID), any(Pageable.class));
        verify(transactionRepository, never()).findByUserIdAndLibraryId(
                anyLong(), eq(OTHER_LIBRARY_ID), any(Pageable.class));
    }

    // ---------- AC: anti-enumeration for staff ----------

    @Test
    void aNonExistentOrForeignUserProducesAnEmptyPageForStaff() {
        // Three cases that must stay indistinguishable: an account that does not
        // exist, one in another library, and one that simply never borrowed
        // anything. All three are an empty page, and none causes a user lookup -
        // resolving the requested account would create the distinction.
        callerIs(ADMIN, 1L, Role.ROLE_ADMIN);
        repositoryReturns(OTHER_USER_ID, List.of(), PageRequest.of(0, 10), 0);

        PagedResponse<TransactionResponse> response = transactionService.getTransactionsByUser(
                OTHER_USER_ID, 0, 10, "id", "asc", ADMIN);

        assertThat(response.getContent()).isEmpty();
        assertThat(response.getTotalElements()).isZero();
        assertThat(response.getTotalPages()).isZero();
        verify(userRepository, never()).findById(anyLong());
    }
}
