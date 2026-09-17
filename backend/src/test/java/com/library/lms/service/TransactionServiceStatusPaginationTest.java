package com.library.lms.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
import com.library.lms.repository.BookRepository;
import com.library.lms.repository.TransactionRepository;
import com.library.lms.repository.UserRepository;

/**
 * Guards the paging, sorting and bounds of the status endpoint.
 *
 * <p>This is the widest answer the API gives: library scoping decides <i>whose</i>
 * loans come back, but not <i>how many</i>, and "every open loan" grows with the
 * library. The page is what bounds it, and the ceiling is what stops a caller
 * asking for the bound to be removed.</p>
 *
 * <p>The sort whitelist is the other half. {@code sortBy} is free text arriving
 * from a URL; passed through to {@code Sort.by} it would be interpreted as an
 * entity property path, so an unknown name must be refused here rather than
 * failing deep inside Hibernate as a 500 that names the entity's internals.</p>
 *
 * <p>Every collaborator is mocked, so no Spring context starts and no row is read
 * or written. The repository is stubbed with a {@link PageImpl}, which is what
 * Spring Data returns; what these tests check is the part that is ours - the
 * validation, the {@link Pageable} handed to the query, and the mapping back to
 * {@link PagedResponse}.</p>
 */
@ExtendWith(MockitoExtension.class)
class TransactionServiceStatusPaginationTest {

    private static final Long LIBRARY_ID = 1L;

    private static final Long OTHER_LIBRARY_ID = 2L;

    private static final String STAFF = "a-librarian";

    private static final String ADMIN = "an-admin";

    private static final String MEMBER = "a-member";

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

    private static User account(String username, Role role) {
        User user = new User();
        user.setId(7L);
        user.setUsername(username);
        user.setRole(role);
        user.setLibrary(library(LIBRARY_ID));
        return user;
    }

    private static Transaction loan(long id) {
        Book book = new Book();
        book.setId(500L);
        book.setLibrary(library(LIBRARY_ID));

        Transaction transaction = new Transaction();
        transaction.setId(id);
        transaction.setBook(book);
        transaction.setUser(account(MEMBER, Role.ROLE_MEMBER));
        transaction.setLibrary(library(LIBRARY_ID));
        transaction.setIssueDate(LocalDate.now().minusDays(5));
        transaction.setDueDate(LocalDate.now().plusDays(9));
        transaction.setStatus(TransactionStatus.ISSUED);
        return transaction;
    }

    private static List<Transaction> loans(int count) {
        return IntStream.rangeClosed(1, count).mapToObj(i -> loan(i)).toList();
    }

    private void callerIs(String username, Role role) {
        when(userRepository.findByUsername(username)).thenReturn(Optional.of(account(username, role)));
    }

    /** Stubs the scoped query with one page carved out of a larger total. */
    private void repositoryReturns(List<Transaction> content, Pageable pageable, long total) {
        when(transactionRepository.findByStatusInAndDueDateGreaterThanEqualAndLibraryId(
                eq(OverduePolicy.OPEN_STATUSES), any(LocalDate.class), eq(LIBRARY_ID), any(Pageable.class)))
                .thenReturn(new PageImpl<>(content, pageable, total));
    }

    private Pageable capturedPageable() {
        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(transactionRepository).findByStatusInAndDueDateGreaterThanEqualAndLibraryId(
                eq(OverduePolicy.OPEN_STATUSES), any(LocalDate.class), eq(LIBRARY_ID), captor.capture());
        return captor.getValue();
    }

    private PagedResponse<TransactionResponse> callWith(int page, int size, String sortBy,
                                                        String direction, String caller) {
        return transactionService.getTransactionsByStatus(
                TransactionStatus.ISSUED, page, size, sortBy, direction, caller);
    }

    // ---------- default pagination ----------

    @Test
    void defaultPagingAsksForTheFirstPageOfTen() {
        callerIs(STAFF, Role.ROLE_LIBRARIAN);
        repositoryReturns(loans(10), PageRequest.of(0, 10), 10);

        callWith(0, 10, "id", "asc", STAFF);

        Pageable used = capturedPageable();
        assertThat(used.getPageNumber()).isZero();
        assertThat(used.getPageSize()).isEqualTo(10);
    }

    @Test
    void aCustomPageAndSizeReachTheQuery() {
        callerIs(STAFF, Role.ROLE_LIBRARIAN);
        repositoryReturns(loans(5), PageRequest.of(2, 5), 40);

        PagedResponse<TransactionResponse> response = callWith(2, 5, "id", "asc", STAFF);

        Pageable used = capturedPageable();
        assertThat(used.getPageNumber()).isEqualTo(2);
        assertThat(used.getPageSize()).isEqualTo(5);
        assertThat(response.getPage()).isEqualTo(2);
        assertThat(response.getSize()).isEqualTo(5);
    }

    @Test
    void totalsDescribeTheWholeMatchingSetNotThePage() {
        callerIs(STAFF, Role.ROLE_LIBRARIAN);
        repositoryReturns(loans(10), PageRequest.of(0, 10), 43);

        PagedResponse<TransactionResponse> response = callWith(0, 10, "id", "asc", STAFF);

        assertThat(response.getContent()).hasSize(10);
        assertThat(response.getTotalElements()).isEqualTo(43);
        assertThat(response.getTotalPages()).isEqualTo(5);
    }

    @Test
    void contentIsMappedToResponsesNotEntities() {
        callerIs(STAFF, Role.ROLE_LIBRARIAN);
        repositoryReturns(loans(3), PageRequest.of(0, 10), 3);

        PagedResponse<TransactionResponse> response = callWith(0, 10, "id", "asc", STAFF);

        assertThat(response.getContent()).hasSize(3);
        assertThat(response.getContent())
                .extracting(TransactionResponse::getId).containsExactly(1L, 2L, 3L);
        assertThat(response.getContent().get(0).getStatus()).isEqualTo(TransactionStatus.ISSUED);
        assertThat(response.getContent().get(0).getBookId()).isEqualTo(500L);
    }

    @Test
    void anEmptyResultIsAnEmptyPageNotAnError() {
        callerIs(STAFF, Role.ROLE_LIBRARIAN);
        repositoryReturns(List.of(), PageRequest.of(0, 10), 0);

        PagedResponse<TransactionResponse> response = callWith(0, 10, "id", "asc", STAFF);

        assertThat(response.getContent()).isEmpty();
        assertThat(response.getTotalElements()).isZero();
        assertThat(response.getTotalPages()).isZero();
    }

    // ---------- sorting ----------

    @Test
    void theDefaultSortIsIdAscending() {
        callerIs(STAFF, Role.ROLE_LIBRARIAN);
        repositoryReturns(loans(2), PageRequest.of(0, 10), 2);

        callWith(0, 10, "id", "asc", STAFF);

        Sort.Order order = capturedPageable().getSort().getOrderFor("id");
        assertThat(order).isNotNull();
        assertThat(order.getDirection()).isEqualTo(Sort.Direction.ASC);
    }

    @Test
    void aWhitelistedSortFieldIsHonoured() {
        callerIs(STAFF, Role.ROLE_LIBRARIAN);
        repositoryReturns(loans(2), PageRequest.of(0, 10), 2);

        callWith(0, 10, "dueDate", "asc", STAFF);

        Sort sort = capturedPageable().getSort();
        assertThat(sort.getOrderFor("dueDate")).isNotNull();
        // A tiebreaker keeps paging stable when several loans share a due date.
        assertThat(sort.getOrderFor("id")).isNotNull();
    }

    @Test
    void descendingDirectionIsHonoured() {
        callerIs(STAFF, Role.ROLE_LIBRARIAN);
        repositoryReturns(loans(2), PageRequest.of(0, 10), 2);

        callWith(0, 10, "issueDate", "desc", STAFF);

        Sort.Order order = capturedPageable().getSort().getOrderFor("issueDate");
        assertThat(order).isNotNull();
        assertThat(order.getDirection()).isEqualTo(Sort.Direction.DESC);
    }

    @Test
    void everyPromisedSortFieldIsAccepted() {
        callerIs(STAFF, Role.ROLE_LIBRARIAN);
        when(transactionRepository.findByStatusInAndDueDateGreaterThanEqualAndLibraryId(
                eq(OverduePolicy.OPEN_STATUSES), any(LocalDate.class), eq(LIBRARY_ID), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        for (String field : List.of("id", "issueDate", "dueDate", "returnDate", "status")) {
            assertThat(callWith(0, 10, field, "asc", STAFF)).as(field).isNotNull();
        }
    }

    @Test
    void anUnknownSortFieldIsRefused() {
        callerIs(STAFF, Role.ROLE_LIBRARIAN);

        assertThatThrownBy(() -> callWith(0, 10, "fineAmount", "asc", STAFF))
                .isInstanceOf(InvalidSortException.class);
    }

    @Test
    void anArbitrarySortPropertyNeverReachesTheQuery() {
        // sortBy is free text from a URL. Passed through it would be read as an
        // entity property path; the whitelist is what stops that.
        callerIs(STAFF, Role.ROLE_LIBRARIAN);

        assertThatThrownBy(() -> callWith(0, 10, "user.password", "asc", STAFF))
                .isInstanceOf(InvalidSortException.class);

        verify(transactionRepository, never())
                .findByStatusInAndDueDateGreaterThanEqualAndLibraryId(any(), any(), any(), any());
    }

    @Test
    void anUnknownDirectionIsRefused() {
        callerIs(STAFF, Role.ROLE_LIBRARIAN);

        assertThatThrownBy(() -> callWith(0, 10, "id", "sideways", STAFF))
                .isInstanceOf(InvalidSortException.class);
    }

    // ---------- bounds ----------

    @Test
    void aNegativePageIsRefused() {
        callerIs(STAFF, Role.ROLE_LIBRARIAN);

        assertThatThrownBy(() -> callWith(-1, 10, "id", "asc", STAFF))
                .isInstanceOf(InvalidPaginationException.class);
    }

    @Test
    void aSizeBelowOneIsRefused() {
        callerIs(STAFF, Role.ROLE_LIBRARIAN);

        assertThatThrownBy(() -> callWith(0, 0, "id", "asc", STAFF))
                .isInstanceOf(InvalidPaginationException.class);
    }

    @Test
    void aSizeAboveTheCeilingIsRefused() {
        callerIs(STAFF, Role.ROLE_LIBRARIAN);

        assertThatThrownBy(() -> callWith(0, 51, "id", "asc", STAFF))
                .isInstanceOf(InvalidPaginationException.class);
    }

    @Test
    void theCeilingItselfIsAccepted() {
        callerIs(STAFF, Role.ROLE_LIBRARIAN);
        repositoryReturns(loans(50), PageRequest.of(0, 50), 50);

        PagedResponse<TransactionResponse> response = callWith(0, 50, "id", "asc", STAFF);

        assertThat(response.getSize()).isEqualTo(50);
    }

    @Test
    void aRefusedPageNeverReachesTheDatabase() {
        callerIs(STAFF, Role.ROLE_LIBRARIAN);

        assertThatThrownBy(() -> callWith(0, 1_000_000, "id", "asc", STAFF))
                .isInstanceOf(InvalidPaginationException.class);

        verify(transactionRepository, never())
                .findByStatusInAndDueDateGreaterThanEqualAndLibraryId(any(), any(), any(), any());
    }

    // ---------- library isolation and authorization ----------

    @Test
    void theQueryIsScopedToTheCallersLibrary() {
        callerIs(STAFF, Role.ROLE_LIBRARIAN);
        repositoryReturns(loans(2), PageRequest.of(0, 10), 2);

        callWith(0, 10, "id", "asc", STAFF);

        // Never another library's id, and never anything the caller supplied.
        verify(transactionRepository).findByStatusInAndDueDateGreaterThanEqualAndLibraryId(
                eq(OverduePolicy.OPEN_STATUSES), any(LocalDate.class), eq(LIBRARY_ID), any(Pageable.class));
        verify(transactionRepository, never()).findByStatusInAndDueDateGreaterThanEqualAndLibraryId(
                any(), any(), eq(OTHER_LIBRARY_ID), any(Pageable.class));
    }

    @Test
    void aLibrarianMayReadTheirOwnLibrarysLoans() {
        callerIs(STAFF, Role.ROLE_LIBRARIAN);
        repositoryReturns(loans(1), PageRequest.of(0, 10), 1);

        assertThat(callWith(0, 10, "id", "asc", STAFF).getContent()).hasSize(1);
    }

    @Test
    void anAdminMayReadTheirOwnLibrarysLoans() {
        callerIs(ADMIN, Role.ROLE_ADMIN);
        when(transactionRepository.findByStatusInAndDueDateGreaterThanEqualAndLibraryId(
                eq(OverduePolicy.OPEN_STATUSES), any(LocalDate.class), eq(LIBRARY_ID), any(Pageable.class)))
                .thenReturn(new PageImpl<>(loans(1), PageRequest.of(0, 10), 1));

        assertThat(transactionService.getTransactionsByStatus(
                TransactionStatus.ISSUED, 0, 10, "id", "asc", ADMIN).getContent()).hasSize(1);
    }

    @Test
    void aMemberIsStoppedBySecurityRatherThanByThisService() {
        // Worth being precise about where the refusal lives. This endpoint is
        // ADMIN/LIBRARIAN only, and that rule is enforced by the filter chain
        // (SecurityConfig: /api/transactions/status/** hasAnyAuthority) before
        // the controller is reached - the service carries no role check of its
        // own for status queries, and this step deliberately did not add one.
        // What the service does guarantee is that a caller only ever sees their
        // own library, whatever their role, and that is asserted here so the
        // tenant boundary cannot quietly become role-dependent.
        callerIs(MEMBER, Role.ROLE_MEMBER);
        repositoryReturns(loans(1), PageRequest.of(0, 10), 1);

        callWith(0, 10, "id", "asc", MEMBER);

        verify(transactionRepository).findByStatusInAndDueDateGreaterThanEqualAndLibraryId(
                eq(OverduePolicy.OPEN_STATUSES), any(LocalDate.class), eq(LIBRARY_ID), any(Pageable.class));
        verify(transactionRepository, never()).findByStatusInAndDueDateGreaterThanEqualAndLibraryId(
                any(), any(), eq(OTHER_LIBRARY_ID), any(Pageable.class));
    }

    @Test
    void statusFilteringStillReachesTheQuery() {
        callerIs(STAFF, Role.ROLE_LIBRARIAN);
        when(transactionRepository.findByStatusAndLibraryId(
                eq(TransactionStatus.RETURNED), eq(LIBRARY_ID), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        transactionService.getTransactionsByStatus(
                TransactionStatus.RETURNED, 0, 10, "id", "asc", STAFF);

        verify(transactionRepository).findByStatusAndLibraryId(
                eq(TransactionStatus.RETURNED), eq(LIBRARY_ID), any(Pageable.class));
    }
}
