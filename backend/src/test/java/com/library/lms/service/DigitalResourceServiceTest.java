package com.library.lms.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import com.library.lms.dto.DigitalResourceRequest;
import com.library.lms.dto.DigitalResourceResponse;
import com.library.lms.entity.Book;
import com.library.lms.entity.DigitalResource;
import com.library.lms.entity.Library;
import com.library.lms.entity.ResourceType;
import com.library.lms.entity.Role;
import com.library.lms.entity.User;
import com.library.lms.exception.BookNotFoundException;
import com.library.lms.exception.DigitalResourceAccessDeniedException;
import com.library.lms.exception.DigitalResourceNotFoundException;
import com.library.lms.exception.InvalidPaginationException;
import com.library.lms.exception.InvalidSortException;
import com.library.lms.repository.BookRepository;
import com.library.lms.repository.DigitalResourceRepository;
import com.library.lms.repository.UserRepository;

/**
 * Who may change a library's digital resources, who may see them, and which
 * library's they are.
 *
 * <p>No database: the repositories are mocks, so what the service asks for can
 * be read argument by argument - which is where library isolation and the
 * enabled rule actually live.</p>
 */
class DigitalResourceServiceTest {

    private static final long LIBRARY_ID = 7L;

    private static final long BOOK_ID = 31L;

    private static final long RESOURCE_ID = 55L;

    private static final String URL = "https://files.example.invalid/chapter-one.pdf";

    private final DigitalResourceRepository resourceRepository = mock(DigitalResourceRepository.class);

    private final BookRepository bookRepository = mock(BookRepository.class);

    private final UserRepository userRepository = mock(UserRepository.class);

    private final DigitalResourceService service =
            new DigitalResourceService(resourceRepository, bookRepository, userRepository);

    private Library library;
    private Book book;

    @BeforeEach
    void fixtures() {
        library = new Library();
        library.setId(LIBRARY_ID);
        library.setName("Central Library");

        book = new Book();
        book.setId(BOOK_ID);
        book.setTitle("A Book");
        book.setLibrary(library);

        account("admin", Role.ROLE_ADMIN);
        account("librarian", Role.ROLE_LIBRARIAN);
        account("member", Role.ROLE_MEMBER);

        when(bookRepository.findByIdAndLibraryId(BOOK_ID, LIBRARY_ID)).thenReturn(Optional.of(book));
        when(resourceRepository.save(any(DigitalResource.class))).thenAnswer(call -> {
            DigitalResource saved = call.getArgument(0);
            if (saved.getId() == null) {
                saved.setId(RESOURCE_ID);
            }
            return saved;
        });
    }

    private void account(String username, Role role) {
        User user = new User();
        user.setId(role == Role.ROLE_MEMBER ? 21L : 11L);
        user.setUsername(username);
        user.setRole(role);
        user.setLibrary(library);
        when(userRepository.findByUsername(username)).thenReturn(Optional.of(user));
    }

    private static DigitalResourceRequest request() {
        DigitalResourceRequest request = new DigitalResourceRequest();
        request.setBookId(BOOK_ID);
        request.setTitle("Chapter one");
        request.setDescription("The first chapter");
        request.setResourceType(ResourceType.PDF);
        request.setResourceUrl(URL);
        return request;
    }

    private DigitalResource existing(boolean enabled) {
        DigitalResource resource = new DigitalResource();
        resource.setId(RESOURCE_ID);
        resource.setLibrary(library);
        resource.setBook(book);
        resource.setTitle("Chapter one");
        resource.setResourceType(ResourceType.PDF);
        resource.setResourceUrl(URL);
        resource.setEnabled(enabled);
        resource.setCreatedAt(LocalDateTime.now().minusDays(1));
        resource.setUpdatedAt(LocalDateTime.now().minusDays(1));
        when(resourceRepository.findByIdAndLibraryId(RESOURCE_ID, LIBRARY_ID)).thenReturn(Optional.of(resource));
        return resource;
    }

    // ---------- staff write ----------

    @ParameterizedTest
    @ValueSource(strings = {"admin", "librarian"})
    void staffAddAResourceToABookOfTheirLibrary(String staff) {
        DigitalResourceResponse response = service.create(request(), staff);

        assertThat(response.bookId()).isEqualTo(BOOK_ID);
        assertThat(response.title()).isEqualTo("Chapter one");
        assertThat(response.resourceType()).isEqualTo(ResourceType.PDF);
        assertThat(response.resourceUrl()).isEqualTo(URL);
        assertThat(response.enabled()).as("a resource is added to be read").isTrue();
        assertThat(response.createdAt()).isNotNull();
        assertThat(response.updatedAt()).isNotNull();
    }

    @Test
    void aResourceIsCreatedInTheCallersOwnLibrary() {
        service.create(request(), "librarian");

        ArgumentCaptor<DigitalResource> saved = ArgumentCaptor.forClass(DigitalResource.class);
        verify(resourceRepository).save(saved.capture());

        assertThat(saved.getValue().getLibrary().getId())
                .as("the caller's library, never one from the request")
                .isEqualTo(LIBRARY_ID);
        assertThat(saved.getValue().getBook().getId()).isEqualTo(BOOK_ID);
    }

    @Test
    void staffMayTurnAResourceOffAndOnAgain() {
        DigitalResource resource = existing(true);

        assertThat(service.setEnabled(RESOURCE_ID, false, "librarian").enabled()).isFalse();
        assertThat(resource.isEnabled()).isFalse();

        assertThat(service.setEnabled(RESOURCE_ID, true, "librarian").enabled()).isTrue();
    }

    @Test
    void staffMayReplaceAResource() {
        existing(true);
        DigitalResourceRequest request = request();
        request.setTitle("Chapter two");
        request.setResourceType(ResourceType.EPUB);

        DigitalResourceResponse response = service.update(RESOURCE_ID, request, "admin");

        assertThat(response.title()).isEqualTo("Chapter two");
        assertThat(response.resourceType()).isEqualTo(ResourceType.EPUB);
    }

    @Test
    void anUpdateThatOmitsEnabledLeavesItAlone() {
        existing(false);

        assertThat(service.update(RESOURCE_ID, request(), "admin").enabled())
                .as("a disabled resource is not silently turned back on by an edit")
                .isFalse();
    }

    @Test
    void staffMayDeleteAResource() {
        DigitalResource resource = existing(true);

        service.delete(RESOURCE_ID, "admin");

        verify(resourceRepository).delete(resource);
    }

    // ---------- members may not ----------

    @Test
    void aMemberMayNotCreateChangeOrDelete() {
        existing(true);

        assertThatThrownBy(() -> service.create(request(), "member"))
                .isInstanceOf(DigitalResourceAccessDeniedException.class);
        assertThatThrownBy(() -> service.update(RESOURCE_ID, request(), "member"))
                .isInstanceOf(DigitalResourceAccessDeniedException.class);
        assertThatThrownBy(() -> service.setEnabled(RESOURCE_ID, false, "member"))
                .isInstanceOf(DigitalResourceAccessDeniedException.class);
        assertThatThrownBy(() -> service.delete(RESOURCE_ID, "member"))
                .isInstanceOf(DigitalResourceAccessDeniedException.class);

        verify(resourceRepository, never()).save(any(DigitalResource.class));
        verify(resourceRepository, never()).delete(any(DigitalResource.class));
    }

    // ---------- one library only ----------

    @Test
    void aResourceOfAnotherLibraryIsNotFound() {
        when(resourceRepository.findByIdAndLibraryId(999L, LIBRARY_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(999L, request(), "admin"))
                .isInstanceOf(DigitalResourceNotFoundException.class);
        assertThatThrownBy(() -> service.delete(999L, "admin"))
                .isInstanceOf(DigitalResourceNotFoundException.class);
        assertThatThrownBy(() -> service.setEnabled(999L, true, "admin"))
                .isInstanceOf(DigitalResourceNotFoundException.class);
    }

    @Test
    void aBookOfAnotherLibraryCannotBeAttachedTo() {
        when(bookRepository.findByIdAndLibraryId(999L, LIBRARY_ID)).thenReturn(Optional.empty());
        DigitalResourceRequest request = request();
        request.setBookId(999L);

        assertThatThrownBy(() -> service.create(request, "admin"))
                .as("the same 404 a book that never existed gets")
                .isInstanceOf(BookNotFoundException.class);
        verify(resourceRepository, never()).save(any(DigitalResource.class));
    }

    @Test
    void everyLookupNamesTheCallersLibrary() {
        existing(true);

        service.getById(RESOURCE_ID, "admin");

        verify(resourceRepository).findByIdAndLibraryId(RESOURCE_ID, LIBRARY_ID);
    }

    // ---------- enabled decides what a member sees ----------

    @Test
    void aMemberReadsOnlyEnabledResources() {
        when(resourceRepository.findByLibraryIdAndEnabledTrue(eq(LIBRARY_ID), any(Pageable.class)))
                .thenReturn(Page.empty());

        service.list(0, 10, "createdAt", "desc", null, "member");

        verify(resourceRepository).findByLibraryIdAndEnabledTrue(eq(LIBRARY_ID), any(Pageable.class));
        verify(resourceRepository, never()).findByLibraryId(anyLong(), any(Pageable.class));
    }

    @Test
    void staffSeeEveryResourceIncludingTheDisabledOnes() {
        when(resourceRepository.findByLibraryId(eq(LIBRARY_ID), any(Pageable.class))).thenReturn(Page.empty());

        service.list(0, 10, "createdAt", "desc", null, "librarian");

        verify(resourceRepository).findByLibraryId(eq(LIBRARY_ID), any(Pageable.class));
        verify(resourceRepository, never()).findByLibraryIdAndEnabledTrue(anyLong(), any(Pageable.class));
    }

    @Test
    void aMembersBookFilterStaysOnTheEnabledOnes() {
        when(resourceRepository.findByLibraryIdAndBookIdAndEnabledTrue(eq(LIBRARY_ID), eq(BOOK_ID),
                any(Pageable.class))).thenReturn(Page.empty());

        service.list(0, 10, "createdAt", "desc", BOOK_ID, "member");

        verify(resourceRepository).findByLibraryIdAndBookIdAndEnabledTrue(eq(LIBRARY_ID), eq(BOOK_ID),
                any(Pageable.class));
    }

    @Test
    void aDisabledResourceIsNotFoundByAMember() {
        when(resourceRepository.findByIdAndLibraryIdAndEnabledTrue(RESOURCE_ID, LIBRARY_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getById(RESOURCE_ID, "member"))
                .as("the same 404 an id that does not exist gets")
                .isInstanceOf(DigitalResourceNotFoundException.class);
    }

    @Test
    void staffCanStillReadADisabledResource() {
        existing(false);

        assertThat(service.getById(RESOURCE_ID, "admin").enabled()).isFalse();
    }

    // ---------- what may be stored ----------

    @ParameterizedTest
    @ValueSource(strings = {
            "javascript:alert(1)",
            "data:text/html;base64,PHNjcmlwdD4=",
            "file:///etc/passwd",
            "ftp://files.example.invalid/book.pdf",
            "not a url",
            "//files.example.invalid/book.pdf"})
    void aUrlThatIsNotHttpOrHttpsIsRefused(String url) {
        DigitalResourceRequest request = request();
        request.setResourceUrl(url);

        assertThatThrownBy(() -> service.create(request, "admin"))
                .isInstanceOf(IllegalArgumentException.class);
        verify(resourceRepository, never()).save(any(DigitalResource.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://files.example.invalid/book.pdf",
            "http://files.example.invalid/book.pdf",
            "HTTPS://FILES.EXAMPLE.INVALID/BOOK.PDF",
            "https://files.example.invalid/book.pdf?token=abc&page=2"})
    void anHttpOrHttpsUrlIsAccepted(String url) {
        DigitalResourceRequest request = request();
        request.setResourceUrl(url);

        assertThatCode(() -> service.create(request, "admin")).doesNotThrowAnyException();
    }

    @Test
    void noFileBytesCanBeStoredOnAResource() {
        assertThat(java.util.Arrays.stream(DigitalResource.class.getDeclaredFields())
                .map(field -> field.getType().getSimpleName())
                .toList())
                .as("a reference, never the file")
                .doesNotContain("byte[]", "Blob", "InputStream");
    }

    // ---------- pages ----------

    @Test
    void aPageIsRefusedWhenItIsNegativeEmptyOrTooWide() {
        for (int[] pageAndSize : new int[][] {{-1, 10}, {0, 0}, {0, 51}}) {
            assertThatThrownBy(() -> service.list(pageAndSize[0], pageAndSize[1], "createdAt", "desc", null,
                    "admin"))
                    .isInstanceOf(InvalidPaginationException.class);
        }
    }

    @Test
    void onlyTheListedFieldsCanBeSortedOn() {
        for (String field : new String[] {"resourceUrl", "library", "enabled", "password"}) {
            assertThatThrownBy(() -> service.list(0, 10, field, "desc", null, "admin"))
                    .isInstanceOf(InvalidSortException.class);
        }

        assertThatThrownBy(() -> service.list(0, 10, "createdAt", "sideways", null, "admin"))
                .isInstanceOf(InvalidSortException.class);
    }
}
