package com.library.lms.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import com.library.lms.dto.CategoryRequest;
import com.library.lms.dto.CategoryResponse;
import com.library.lms.dto.PagedResponse;
import com.library.lms.entity.Category;
import com.library.lms.entity.Library;
import com.library.lms.entity.Role;
import com.library.lms.entity.User;
import com.library.lms.exception.CategoryInUseException;
import com.library.lms.exception.CategoryNotFoundException;
import com.library.lms.exception.DuplicateCategoryException;
import com.library.lms.repository.BookRepository;
import com.library.lms.repository.CategoryRepository;
import com.library.lms.repository.UserRepository;

/**
 * Guards the library boundary on category listing, update and delete.
 *
 * <p>Every collaborator is mocked, so no Spring context starts and no row is
 * read or written.</p>
 *
 * <p>The load-bearing assertions are the negative ones. A caller from one
 * library must not be able to see, rename or remove another library's
 * categories - and must not be able to tell the difference between "that
 * category belongs to someone else" and "that category does not exist", or the
 * endpoint becomes a way to enumerate a neighbour's shelves one id at a
 * time.</p>
 */
@ExtendWith(MockitoExtension.class)
class CategoryServiceLibraryScopingTest {

    private static final String CALLER = "a-librarian";

    private static final Long OWN_LIBRARY_ID = 1L;

    private static final Long OTHER_LIBRARY_ID = 2L;

    private static final Long CATEGORY_ID = 7L;

    @Mock
    private CategoryRepository categoryRepository;

    @Mock
    private BookRepository bookRepository;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private CategoryService categoryService;

    private static Library library(Long id) {
        Library library = new Library();
        library.setId(id);
        library.setName("Library " + id);
        return library;
    }

    private static Category category(Long id, String name, Long libraryId) {
        Category category = new Category();
        category.setId(id);
        category.setName(name);
        category.setLibrary(library(libraryId));
        return category;
    }

    /** The caller belongs to OWN_LIBRARY_ID. */
    private void callerIsInOwnLibrary() {
        User user = new User();
        user.setId(10L);
        user.setUsername(CALLER);
        user.setRole(Role.ROLE_LIBRARIAN);
        user.setLibrary(library(OWN_LIBRARY_ID));
        when(userRepository.findByUsername(CALLER)).thenReturn(Optional.of(user));
    }

    private static CategoryRequest request(String name) {
        CategoryRequest request = new CategoryRequest();
        request.setName(name);
        return request;
    }

    // ---------- listing ----------

    @Test
    void listReturnsOnlyTheCallersOwnLibraryCategories() {
        callerIsInOwnLibrary();
        when(categoryRepository.findByLibraryId(eq(OWN_LIBRARY_ID), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(category(1L, "Fiction", OWN_LIBRARY_ID),
                        category(2L, "History", OWN_LIBRARY_ID))));

        PagedResponse<CategoryResponse> categories = categoryService.getAllCategories(0, 10, "id", "asc", CALLER);

        assertThat(categories.getContent()).extracting(CategoryResponse::getName)
                .containsExactly("Fiction", "History");
        verify(categoryRepository).findByLibraryId(eq(OWN_LIBRARY_ID), any(Pageable.class));
    }

    @Test
    void listNeverReadsEveryLibrarysCategories() {
        // The tenant filter must be in the query. Loading all rows and
        // discarding other libraries' would still have read them.
        callerIsInOwnLibrary();
        when(categoryRepository.findByLibraryId(eq(OWN_LIBRARY_ID), any(Pageable.class))).thenReturn(Page.empty());

        categoryService.getAllCategories(0, 10, "id", "asc", CALLER);

        verify(categoryRepository, never()).findAll();
        verify(categoryRepository, never()).findAll(any(org.springframework.data.domain.Sort.class));
        verify(categoryRepository, never()).findAll(any(Pageable.class));
    }

    // ---------- update ----------

    @Test
    void updateIsRefusedForACategoryInAnotherLibrary() {
        callerIsInOwnLibrary();
        // The scoped lookup finds nothing, because category 7 belongs to library 2.
        when(categoryRepository.findByIdAndLibraryId(CATEGORY_ID, OWN_LIBRARY_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> categoryService.updateCategory(CATEGORY_ID, request("Renamed"), CALLER))
                .isInstanceOf(CategoryNotFoundException.class);
    }

    @Test
    void refusedUpdateNeverReachesTheProtectedOperation() {
        callerIsInOwnLibrary();
        when(categoryRepository.findByIdAndLibraryId(CATEGORY_ID, OWN_LIBRARY_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> categoryService.updateCategory(CATEGORY_ID, request("Renamed"), CALLER))
                .isInstanceOf(CategoryNotFoundException.class);

        verify(categoryRepository, never())
                .existsByLibraryIdAndNameIgnoreCaseAndIdNot(anyLong(), anyString(), anyLong());
        verify(categoryRepository, never()).save(any(Category.class));
    }

    @Test
    void updateSucceedsForACategoryInTheCallersOwnLibrary() {
        callerIsInOwnLibrary();
        Category own = category(CATEGORY_ID, "Fiction", OWN_LIBRARY_ID);
        when(categoryRepository.findByIdAndLibraryId(CATEGORY_ID, OWN_LIBRARY_ID))
                .thenReturn(Optional.of(own));
        when(categoryRepository.existsByLibraryIdAndNameIgnoreCaseAndIdNot(OWN_LIBRARY_ID, "Classics", CATEGORY_ID))
                .thenReturn(false);
        when(categoryRepository.save(any(Category.class))).thenAnswer(i -> i.getArgument(0));

        CategoryResponse response = categoryService.updateCategory(CATEGORY_ID, request("  Classics  "), CALLER);

        assertThat(response.getName()).isEqualTo("Classics");
        assertThat(own.getName()).as("trimmed before saving, as before").isEqualTo("Classics");
    }

    @Test
    void duplicateNameIsStillRejectedOnUpdate() {
        callerIsInOwnLibrary();
        when(categoryRepository.findByIdAndLibraryId(CATEGORY_ID, OWN_LIBRARY_ID))
                .thenReturn(Optional.of(category(CATEGORY_ID, "Fiction", OWN_LIBRARY_ID)));
        when(categoryRepository.existsByLibraryIdAndNameIgnoreCaseAndIdNot(OWN_LIBRARY_ID, "History", CATEGORY_ID))
                .thenReturn(true);

        assertThatThrownBy(() -> categoryService.updateCategory(CATEGORY_ID, request("History"), CALLER))
                .isInstanceOf(DuplicateCategoryException.class);

        verify(categoryRepository, never()).save(any(Category.class));
    }

    // ---------- delete ----------

    @Test
    void deleteIsRefusedForACategoryInAnotherLibrary() {
        callerIsInOwnLibrary();
        when(categoryRepository.findByIdAndLibraryId(CATEGORY_ID, OWN_LIBRARY_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> categoryService.deleteCategory(CATEGORY_ID, CALLER))
                .isInstanceOf(CategoryNotFoundException.class);
    }

    @Test
    void refusedDeleteNeverReachesTheProtectedOperation() {
        callerIsInOwnLibrary();
        when(categoryRepository.findByIdAndLibraryId(CATEGORY_ID, OWN_LIBRARY_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> categoryService.deleteCategory(CATEGORY_ID, CALLER))
                .isInstanceOf(CategoryNotFoundException.class);

        verify(bookRepository, never()).existsByCategoryId(anyLong());
        verify(categoryRepository, never()).delete(any(Category.class));
    }

    @Test
    void deleteSucceedsForAnUnusedCategoryInTheCallersOwnLibrary() {
        callerIsInOwnLibrary();
        Category own = category(CATEGORY_ID, "Fiction", OWN_LIBRARY_ID);
        when(categoryRepository.findByIdAndLibraryId(CATEGORY_ID, OWN_LIBRARY_ID))
                .thenReturn(Optional.of(own));
        when(bookRepository.existsByCategoryId(CATEGORY_ID)).thenReturn(false);

        categoryService.deleteCategory(CATEGORY_ID, CALLER);

        verify(categoryRepository).delete(own);
    }

    @Test
    void categoryInUseIsStillRejectedInTheCallersOwnLibrary() {
        callerIsInOwnLibrary();
        when(categoryRepository.findByIdAndLibraryId(CATEGORY_ID, OWN_LIBRARY_ID))
                .thenReturn(Optional.of(category(CATEGORY_ID, "Fiction", OWN_LIBRARY_ID)));
        when(bookRepository.existsByCategoryId(CATEGORY_ID)).thenReturn(true);

        assertThatThrownBy(() -> categoryService.deleteCategory(CATEGORY_ID, CALLER))
                .isInstanceOf(CategoryInUseException.class);

        verify(categoryRepository, never()).delete(any(Category.class));
    }

    // ---------- non-disclosure ----------

    @Test
    void anotherLibrarysCategoryIsIndistinguishableFromOneThatDoesNotExist() {
        // Both cases go through the same scoped lookup and produce the same
        // exception with the same message, so a caller walking ids learns
        // nothing about which of them are real elsewhere.
        callerIsInOwnLibrary();
        when(categoryRepository.findByIdAndLibraryId(anyLong(), anyLong())).thenReturn(Optional.empty());

        Throwable foreign = catchThrowable(() -> categoryService.deleteCategory(CATEGORY_ID, CALLER));
        Throwable missing = catchThrowable(() -> categoryService.deleteCategory(4242L, CALLER));

        assertThat(foreign).isInstanceOf(CategoryNotFoundException.class);
        assertThat(missing).isInstanceOf(CategoryNotFoundException.class);
        assertThat(missing.getClass()).isEqualTo(foreign.getClass());

        // Neither message names a library.
        assertThat(foreign.getMessage()).doesNotContain(String.valueOf(OTHER_LIBRARY_ID))
                .doesNotContainIgnoringCase("library");
        assertThat(missing.getMessage()).doesNotContainIgnoringCase("library");
    }

    @Test
    void anUnresolvableAuthenticatedNameNeverReachesTheCategoryTable() {
        when(userRepository.findByUsername("ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> categoryService.getAllCategories(0, 10, "id", "asc", "ghost"))
                .isInstanceOf(com.library.lms.exception.UserNotFoundException.class);

        verify(categoryRepository, never()).findByLibraryId(anyLong(), any(Pageable.class));
    }

    // ---------- library-scoped name uniqueness ----------

    @Test
    void sameLibrarySameNameIsRejectedOnCreate() {
        callerIsInOwnLibrary();
        when(categoryRepository.existsByLibraryIdAndNameIgnoreCase(OWN_LIBRARY_ID, "Fiction")).thenReturn(true);

        assertThatThrownBy(() -> categoryService.createCategory(request("Fiction"), CALLER))
                .isInstanceOf(DuplicateCategoryException.class);

        verify(categoryRepository, never()).save(any(Category.class));
    }

    @Test
    void sameLibraryDifferentCaseIsRejectedOnCreate() {
        // Case folding itself belongs to the derived query and the column's
        // ai_ci collation; what this pins down is that the service asks the
        // IgnoreCase variant, so "fiction" reaches the same check as "Fiction".
        callerIsInOwnLibrary();
        when(categoryRepository.existsByLibraryIdAndNameIgnoreCase(OWN_LIBRARY_ID, "fiction")).thenReturn(true);

        assertThatThrownBy(() -> categoryService.createCategory(request("  fiction  "), CALLER))
                .isInstanceOf(DuplicateCategoryException.class);

        verify(categoryRepository).existsByLibraryIdAndNameIgnoreCase(OWN_LIBRARY_ID, "fiction");
        verify(categoryRepository, never()).save(any(Category.class));
    }

    @Test
    void aDifferentLibraryMayUseTheSameName() {
        // The whole point of the change: library 2 asking for "Fiction" is not a
        // clash with library 1's "Fiction", so its own scoped check comes back
        // clean and the category is created.
        User other = new User();
        other.setId(20L);
        other.setUsername("other-librarian");
        other.setRole(Role.ROLE_LIBRARIAN);
        other.setLibrary(library(OTHER_LIBRARY_ID));
        when(userRepository.findByUsername("other-librarian")).thenReturn(Optional.of(other));
        when(categoryRepository.existsByLibraryIdAndNameIgnoreCase(OTHER_LIBRARY_ID, "Fiction")).thenReturn(false);
        when(categoryRepository.save(any(Category.class))).thenAnswer(i -> i.getArgument(0));

        CategoryResponse response = categoryService.createCategory(request("Fiction"), "other-librarian");

        assertThat(response.getName()).isEqualTo("Fiction");
    }

    @Test
    void createChecksDuplicatesAgainstTheAuthenticatedUsersLibrary() {
        callerIsInOwnLibrary();
        when(categoryRepository.existsByLibraryIdAndNameIgnoreCase(OWN_LIBRARY_ID, "Poetry")).thenReturn(false);
        when(categoryRepository.save(any(Category.class))).thenAnswer(i -> i.getArgument(0));

        categoryService.createCategory(request("Poetry"), CALLER);

        // The library used for the check is the caller's, never a client value.
        verify(categoryRepository).existsByLibraryIdAndNameIgnoreCase(OWN_LIBRARY_ID, "Poetry");
        verify(categoryRepository, never()).existsByLibraryIdAndNameIgnoreCase(eq(OTHER_LIBRARY_ID), anyString());

        ArgumentCaptor<Category> saved = ArgumentCaptor.forClass(Category.class);
        verify(categoryRepository).save(saved.capture());
        assertThat(saved.getValue().getLibrary().getId()).isEqualTo(OWN_LIBRARY_ID);
    }

    @Test
    void updateChecksDuplicatesAgainstTheAuthenticatedUsersLibrary() {
        callerIsInOwnLibrary();
        when(categoryRepository.findByIdAndLibraryId(CATEGORY_ID, OWN_LIBRARY_ID))
                .thenReturn(Optional.of(category(CATEGORY_ID, "Fiction", OWN_LIBRARY_ID)));
        when(categoryRepository.existsByLibraryIdAndNameIgnoreCaseAndIdNot(OWN_LIBRARY_ID, "Poetry", CATEGORY_ID))
                .thenReturn(false);
        when(categoryRepository.save(any(Category.class))).thenAnswer(i -> i.getArgument(0));

        categoryService.updateCategory(CATEGORY_ID, request("Poetry"), CALLER);

        verify(categoryRepository)
                .existsByLibraryIdAndNameIgnoreCaseAndIdNot(OWN_LIBRARY_ID, "Poetry", CATEGORY_ID);
    }
}
