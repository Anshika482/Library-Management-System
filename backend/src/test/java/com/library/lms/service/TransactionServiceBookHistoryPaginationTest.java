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
import com.library.lms.repository.BookRepository;
import com.library.lms.repository.TransactionRepository;
import com.library.lms.repository.UserRepository;

/**
 * Guards the paging, sorting and bounds of the book-history endpoint.
 *
 * <p>A title that has been on the shelves for years accumulates borrowing
 * history without limit. Library scoping decides <i>whose</i> loans come back;
 * it does nothing about <i>how many</i>, which is what the page bounds and what
 * the size ceiling stops a caller removing.</p>
 *
 * <p>The assertions that matter most are the ones about the {@code libraryId}
 * reaching the query. A test that only checked returned content would pass just
 * as happily against an unscoped query returning a neighbouring library's rows,
 * so the tenant predicate is verified on the call itself, not inferred from the
 * answer.</p>
 *
 * <p>Every collaborator is mocked, so no Spring context starts and no row is read
 * or written. The repository is stubbed with a {@link PageImpl}, which is what
 * Spring Data returns; what these tests check is the part that is ours - the
 * validation, the {@link Pageable} handed to the query, and the mapping back to
 * {@link PagedResponse}.</p>
 */
@ExtendWith(MockitoExtension.class)
class TransactionServiceBookHistoryPaginationTest {

    private static final Long LIBRARY_ID = 1L;

    private static final Long OTHER_LIBRARY_ID = 2L;

    private static final String STAFF = "a-librarian";

    private static final Long BOOK_ID = 100L;

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

    private static User staff() {
        User user = new User();
        user.setId(7L);
        user.setUsername(STAFF);
        user.setRole(Role.ROLE_LIBRARIAN);
        user.setLibrary(library(LIBRARY_ID));
        return user;
    }

    private static Transaction loan(long id) {
        Book book = new Book();
        book.setId(BOOK_ID);
        book.setLibrary(library(LIBRARY_ID));

        User borrower = new User();
        borrower.setId(20L);
        borrower.setLibrary(library(LIBRARY_ID));

        Transaction transaction = new Transaction();
        transaction.setId(id);
        transaction.setBook(book);
        transaction.setUser(borrower);
        transaction.setLibrary(library(LIBRARY_ID));
        transaction.setIssueDate(LocalDate.now().minusDays(20));
        transaction.setDueDate(LocalDate.now().minusDays(6));
        transaction.setReturnDate(LocalDate.now().minusDays(8));
        transaction.setStatus(TransactionStatus.RETURNED);
        return transaction;
    }

    private static List<Transaction> loans(int count) {
        return IntStream.rangeClosed(1, count).mapToObj(i -> loan(i)).toList();
    }

    private void callerIsStaff() {
        when(userRepository.findByUsername(STAFF)).thenReturn(Optional.of(staff()));
    }

    private void repositoryReturns(List<Transaction> content, Pageable pageable, long total) {
        when(transactionRepository.findByBookIdAndLibraryId(
                eq(BOOK_ID), eq(LIBRARY_ID), any(Pageable.class)))
                .thenReturn(new PageImpl<>(content, pageable, total));
    }

    private Pageable capturedPageable() {
        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(transactionRepository).findByBookIdAndLibraryId(
                eq(BOOK_ID), eq(LIBRARY_ID), captor.capture());
        return captor.getValue();
    }

    private PagedResponse<TransactionResponse> callWith(int page, int size, String sortBy,
                                                        String direction) {
        return transactionService.getTransactionsByBook(BOOK_ID, page, size, sortBy, direction, STAFF);
    }

    // ---------- A, B: paging ----------

    @Test
    void defaultPagingAsksForTheFirstPageOfTen() {
        callerIsStaff();
        repositoryReturns(loans(10), PageRequest.of(0, 10), 10);

        callWith(0, 10, "id", "asc");

        Pageable used = capturedPageable();
        assertThat(used.getPageNumber()).isZero();
        assertThat(used.getPageSize()).isEqualTo(10);
    }

    @Test
    void aCustomPageAndSizeReachTheQuery() {
        callerIsStaff();
        repositoryReturns(loans(5), PageRequest.of(3, 5), 60);

        PagedResponse<TransactionResponse> response = callWith(3, 5, "id", "asc");

        Pageable used = capturedPageable();
        assertThat(used.getPageNumber()).isEqualTo(3);
        assertThat(used.getPageSize()).isEqualTo(5);
        assertThat(response.getPage()).isEqualTo(3);
        assertThat(response.getSize()).isEqualTo(5);
    }

    // ---------- C-F: bounds ----------

    @Test
    void theMaximumSizeOfFiftyIsAccepted() {
        callerIsStaff();
        repositoryReturns(loans(50), PageRequest.of(0, 50), 50);

        assertThat(callWith(0, 50, "id", "asc").getSize()).isEqualTo(50);
    }

    @Test
    void aSizeAboveFiftyIsRejected() {
        callerIsStaff();

        assertThatThrownBy(() -> callWith(0, 51, "id", "asc"))
                .isInstanceOf(InvalidPaginationException.class);
    }

    @Test
    void aNegativePageIsRejected() {
        callerIsStaff();

        assertThatThrownBy(() -> callWith(-1, 10, "id", "asc"))
                .isInstanceOf(InvalidPaginationException.class);
    }

    @Test
    void aSizeOfZeroOrLessIsRejected() {
        callerIsStaff();

        assertThatThrownBy(() -> callWith(0, 0, "id", "asc"))
                .isInstanceOf(InvalidPaginationException.class);
        assertThatThrownBy(() -> callWith(0, -5, "id", "asc"))
                .isInstanceOf(InvalidPaginationException.class);
    }

    @Test
    void aRejectedPageNeverReachesTheDatabase() {
        callerIsStaff();

        assertThatThrownBy(() -> callWith(0, 1_000_000, "id", "asc"))
                .isInstanceOf(InvalidPaginationException.class);

        verify(transactionRepository, never())
                .findByBookIdAndLibraryId(anyLong(), anyLong(), any(Pageable.class));
    }

    // ---------- G-K: every whitelisted sort field ----------

    @Test
    void sortingByIdIsAccepted() {
        callerIsStaff();
        repositoryReturns(loans(2), PageRequest.of(0, 10), 2);

        callWith(0, 10, "id", "asc");

        assertThat(capturedPageable().getSort().getOrderFor("id")).isNotNull();
    }

    @Test
    void sortingByIssueDateIsAccepted() {
        callerIsStaff();
        repositoryReturns(loans(2), PageRequest.of(0, 10), 2);

        callWith(0, 10, "issueDate", "asc");

        assertThat(capturedPageable().getSort().getOrderFor("issueDate")).isNotNull();
    }

    @Test
    void sortingByDueDateIsAccepted() {
        callerIsStaff();
        repositoryReturns(loans(2), PageRequest.of(0, 10), 2);

        callWith(0, 10, "dueDate", "asc");

        assertThat(capturedPageable().getSort().getOrderFor("dueDate")).isNotNull();
    }

    @Test
    void sortingByReturnDateIsAccepted() {
        callerIsStaff();
        repositoryReturns(loans(2), PageRequest.of(0, 10), 2);

        callWith(0, 10, "returnDate", "asc");

        assertThat(capturedPageable().getSort().getOrderFor("returnDate")).isNotNull();
    }

    @Test
    void sortingByStatusIsAccepted() {
        callerIsStaff();
        repositoryReturns(loans(2), PageRequest.of(0, 10), 2);

        callWith(0, 10, "status", "asc");

        assertThat(capturedPageable().getSort().getOrderFor("status")).isNotNull();
    }

    // ---------- L, M: rejected sorts ----------

    @Test
    void anInvalidSortFieldIsRejected() {
        callerIsStaff();

        assertThatThrownBy(() -> callWith(0, 10, "fineAmount", "asc"))
                .isInstanceOf(InvalidSortException.class);
        assertThatThrownBy(() -> callWith(0, 10, "user.password", "asc"))
                .isInstanceOf(InvalidSortException.class);

        verify(transactionRepository, never())
                .findByBookIdAndLibraryId(anyLong(), anyLong(), any(Pageable.class));
    }

    @Test
    void anInvalidDirectionIsRejected() {
        callerIsStaff();

        assertThatThrownBy(() -> callWith(0, 10, "id", "upwards"))
                .isInstanceOf(InvalidSortException.class);
    }

    // ---------- N, O: stable ordering ----------

    @Test
    void aSecondaryIdSortIsAppendedWhenSortingByAnotherField() {
        // Without it, loans sharing an issue date have no order between them and
        // the same row can appear on two pages or on neither.
        callerIsStaff();
        repositoryReturns(loans(2), PageRequest.of(0, 10), 2);

        callWith(0, 10, "issueDate", "desc");

        Sort sort = capturedPageable().getSort();
        assertThat(sort.getOrderFor("issueDate").getDirection()).isEqualTo(Sort.Direction.DESC);
        Sort.Order tiebreaker = sort.getOrderFor("id");
        assertThat(tiebreaker).as("a stable tiebreaker must be present").isNotNull();
        assertThat(tiebreaker.getDirection()).isEqualTo(Sort.Direction.ASC);
    }

    @Test
    void noRedundantSecondarySortWhenAlreadySortingById() {
        callerIsStaff();
        repositoryReturns(loans(2), PageRequest.of(0, 10), 2);

        callWith(0, 10, "id", "desc");

        Sort sort = capturedPageable().getSort();
        assertThat(sort).hasSize(1);
        assertThat(sort.getOrderFor("id").getDirection()).isEqualTo(Sort.Direction.DESC);
    }

    // ---------- P: the tenant predicate reaches the query ----------

    @Test
    void theCallersLibraryIdIsPassedToTheRepository() {
        callerIsStaff();
        repositoryReturns(loans(2), PageRequest.of(0, 10), 2);

        callWith(0, 10, "id", "asc");

        // Asserted on the call, not on the answer: a test that only checked
        // content would pass against an unscoped query too.
        verify(transactionRepository).findByBookIdAndLibraryId(
                eq(BOOK_ID), eq(LIBRARY_ID), any(Pageable.class));
        verify(transactionRepository, never()).findByBookIdAndLibraryId(
                anyLong(), eq(OTHER_LIBRARY_ID), any(Pageable.class));
    }

    @Test
    void theLibraryIsNeverTakenFromTheCaller() {
        // The only source of the tenant id is the authenticated account, which
        // is why the username is the only identity the method accepts.
        callerIsStaff();
        repositoryReturns(loans(1), PageRequest.of(0, 10), 1);

        callWith(0, 10, "id", "asc");

        verify(userRepository).findByUsername(STAFF);
        verify(transactionRepository).findByBookIdAndLibraryId(
                eq(BOOK_ID), eq(LIBRARY_ID), any(Pageable.class));
    }

    // ---------- Q, R: conversion and empty results ----------

    @Test
    void thePagedRepositoryResultIsConvertedCorrectly() {
        callerIsStaff();
        repositoryReturns(loans(3), PageRequest.of(1, 3), 17);

        PagedResponse<TransactionResponse> response = callWith(1, 3, "id", "asc");

        assertThat(response.getContent()).hasSize(3);
        assertThat(response.getContent())
                .extracting(TransactionResponse::getId).containsExactly(1L, 2L, 3L);
        assertThat(response.getContent().get(0).getBookId()).isEqualTo(BOOK_ID);
        assertThat(response.getContent().get(0).getStatus()).isEqualTo(TransactionStatus.RETURNED);
        assertThat(response.getPage()).isEqualTo(1);
        assertThat(response.getSize()).isEqualTo(3);
        // Totals describe the whole history, not the slice.
        assertThat(response.getTotalElements()).isEqualTo(17);
        assertThat(response.getTotalPages()).isEqualTo(6);
    }

    @Test
    void anEmptyHistoryIsAnEmptyPageNotAnError() {
        callerIsStaff();
        repositoryReturns(List.of(), PageRequest.of(0, 10), 0);

        PagedResponse<TransactionResponse> response = callWith(0, 10, "id", "asc");

        assertThat(response.getContent()).isEmpty();
        assertThat(response.getTotalElements()).isZero();
        assertThat(response.getTotalPages()).isZero();
    }

    @Test
    void aBookWithNoHistoryAndAForeignBookAnswerAlike() {
        // The anti-enumeration property, preserved through pagination: an empty
        // page either way, so a caller cannot map another library's catalogue.
        callerIsStaff();
        repositoryReturns(List.of(), PageRequest.of(0, 10), 0);

        PagedResponse<TransactionResponse> response = callWith(0, 10, "id", "asc");

        assertThat(response.getContent()).isEmpty();
        assertThat(response.getTotalElements()).isZero();
        // No book lookup at all - loading it would create the distinction.
        verify(bookRepository, never()).findByIdAndLibraryId(anyLong(), anyLong());
    }
}
