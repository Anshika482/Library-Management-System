package com.library.lms.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.library.lms.dto.CategoryRequest;
import com.library.lms.dto.CategoryResponse;
import com.library.lms.entity.Category;
import com.library.lms.entity.Library;
import com.library.lms.entity.User;
import com.library.lms.exception.CategoryInUseException;
import com.library.lms.exception.CategoryNotFoundException;
import com.library.lms.exception.DuplicateCategoryException;
import com.library.lms.exception.UserNotFoundException;
import com.library.lms.repository.BookRepository;
import com.library.lms.repository.CategoryRepository;
import com.library.lms.repository.UserRepository;

/**
 * Business logic for reading categories.
 *
 * <p>Supports listing, creating, renaming and deleting. Deletion is guarded:
 * a category still referenced by books is refused rather than removed, so no
 * book is ever orphaned.</p>
 *
 * <p>Like {@link BookService}, this class is the boundary between the entity
 * and the outside world - {@link Category} never leaves it, only
 * {@link CategoryResponse} does.</p>
 */
@Service
public class CategoryService {

    /**
     * Constructor injection, the same pattern BookService uses: the field is
     * final, and with a single constructor Spring wires it without needing
     * {@code @Autowired}.
     */
    private final CategoryRepository categoryRepository;

    /**
     * Read-only use of the book side, needed to answer one question before a
     * delete: "is anything still pointing at this category?". Nothing here ever
     * writes to books.
     */
    private final BookRepository bookRepository;

    private final UserRepository userRepository;

    public CategoryService(CategoryRepository categoryRepository, BookRepository bookRepository,
                           UserRepository userRepository) {
        this.categoryRepository = categoryRepository;
        this.bookRepository = bookRepository;
        this.userRepository = userRepository;
    }

    /**
     * Returns every category, lowest id first.
     *
     * <p>The sort is deliberate. {@code findAll()} with no ordering leaves the
     * order to the database, which is free to return rows however it likes -
     * fine in practice today, but not something a client should rely on.
     * Passing {@code Sort} makes the ordering part of the contract, and it uses
     * the {@code findAll(Sort)} that JpaRepository already provides, so
     * CategoryRepository needs no new method.</p>
     *
     * <p>An empty table gives an empty list, never null.</p>
     */
    public List<CategoryResponse> getAllCategories(String authenticatedUsername) {
        Long libraryId = authenticatedUser(authenticatedUsername).getLibrary().getId();

        return categoryRepository.findByLibraryIdOrderByIdAsc(libraryId)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * Creates a category, refusing names that already exist.
     *
     * <p>The name is <b>trimmed first</b>, and everything downstream uses the
     * trimmed value: the duplicate check, the saved row, and the message in the
     * exception. That ordering is what makes {@code "  Fiction  "} and
     * {@code "Fiction"} the same shelf rather than two.</p>
     *
     * <p>The name cannot be empty at this point. {@code @NotBlank} on
     * CategoryRequest means "at least one non-whitespace character", so a name
     * of only spaces is rejected at the controller boundary and never reaches
     * here - which is precisely the rule "not empty once trimmed".</p>
     *
     * <p>The check is case-insensitive, so "Programming", "programming" and
     * " PROGRAMMING " all collide with the existing row. On a clash nothing is
     * written: {@link DuplicateCategoryException} is thrown before
     * {@code save()}, so the existing category is left exactly as it was.</p>
     *
     * <p><b>The owning library comes from the caller, not the request.</b>
     * {@code CategoryRequest} carries a name and nothing else, deliberately: a
     * libraryId in the body would let any authenticated user file a category
     * into another library by changing a number. The library is read from the
     * account behind {@code authenticatedUsername}, which the controller takes
     * from the authenticated principal.</p>
     *
     * <p>{@code @Transactional} is needed here, and only became so with that
     * lookup. {@code User.library} is a LAZY association and
     * {@code open-in-view} is off, so without a surrounding transaction the
     * account would be loaded and detached in one repository call and the
     * library proxy would be dead by the time {@code save()} ran in the next.
     * One transaction keeps the load and the write in the same persistence
     * context. It also makes the pair atomic, though with a single write that
     * is a secondary benefit.</p>
     *
     * @param request               the submitted name
     * @param authenticatedUsername the caller's login name, which the caller
     *                              must take from the authenticated principal
     * @throws DuplicateCategoryException if the name is already taken
     * @throws UserNotFoundException      if the authenticated name matches no
     *                                    account
     */
    @Transactional
    public CategoryResponse createCategory(CategoryRequest request, String authenticatedUsername) {
        String name = request.getName().trim();

        // The owning library is read from the caller's own account, never from
        // the request. A libraryId in the body would let any authenticated user
        // file a category into somebody else's library by changing a number.
        Library library = authenticatedUser(authenticatedUsername).getLibrary();

        // Scoped to that library. Another library holding this name is not a
        // clash, and treating it as one is what the global check got wrong: the
        // second library could never create its own "Fiction".
        if (categoryRepository.existsByLibraryIdAndNameIgnoreCase(library.getId(), name)) {
            throw new DuplicateCategoryException(name);
        }

        Category category = new Category();
        category.setName(name);
        category.setLibrary(library);

        return toResponse(categoryRepository.save(category));
    }

    /**
     * Renames an existing category.
     *
     * <p>Three things happen in order, and the order is the whole design:</p>
     * <ol>
     *   <li>Load the row, failing with {@link CategoryNotFoundException} if the
     *       id is unknown - so a rename never silently creates anything.</li>
     *   <li>Trim, exactly as create does, so "  Fiction  " and "Fiction" are
     *       the same name rather than two.</li>
     *   <li>Check the trimmed name against every <b>other</b> category.</li>
     * </ol>
     *
     * <p>That last point is the one worth being careful about. The check
     * excludes the category being edited, so a category may keep its own name -
     * submitting "Fiction" for the category already called "Fiction", or fixing
     * its capitalisation to "fiction", both succeed. Only a name belonging to a
     * different category is a conflict.</p>
     *
     * <p>Both checks run before {@code save()}, so a rejected update leaves the
     * category exactly as it was.</p>
     *
     * <p>The id comes from the URL, never from the request body - CategoryRequest
     * carries only a name, so a client cannot reassign a row by editing JSON.</p>
     *
     * @throws CategoryNotFoundException  if no category has this id
     * @throws DuplicateCategoryException if another category already uses the name
     */
    public CategoryResponse updateCategory(Long id, CategoryRequest request, String authenticatedUsername) {
        Long libraryId = authenticatedUser(authenticatedUsername).getLibrary().getId();

        Category existingCategory = categoryRepository.findByIdAndLibraryId(id, libraryId)
                .orElseThrow(() -> new CategoryNotFoundException(id));

        String name = request.getName().trim();

        if (categoryRepository.existsByLibraryIdAndNameIgnoreCaseAndIdNot(libraryId, name, id)) {
            throw new DuplicateCategoryException(name);
        }

        existingCategory.setName(name);

        return toResponse(categoryRepository.save(existingCategory));
    }

    /**
     * Deletes a category, but only when no book is using it.
     *
     * <p>Two guards, in order:</p>
     * <ol>
     *   <li>The category must exist, otherwise {@link CategoryNotFoundException}
     *       - so deleting an unknown id reports the truth rather than quietly
     *       succeeding.</li>
     *   <li>No book may reference it, otherwise {@link CategoryInUseException}.</li>
     * </ol>
     *
     * <p>The second guard is the important one. {@code books.category_id} is a
     * real foreign key, so deleting a category in use would either be refused
     * by the database with a raw constraint error, or - if the mapping were
     * ever changed to cascade - destroy the books themselves. Checking first
     * avoids both: nothing is written, no book is touched, and the caller gets
     * a 409 naming the category.</p>
     *
     * <p>The already-loaded entity is passed to {@code delete}, so the row is
     * not looked up twice.</p>
     *
     * @throws CategoryNotFoundException if no category has this id
     * @throws CategoryInUseException    if at least one book references it
     */
    public void deleteCategory(Long id, String authenticatedUsername) {
        Long libraryId = authenticatedUser(authenticatedUsername).getLibrary().getId();

        Category category = categoryRepository.findByIdAndLibraryId(id, libraryId)
                .orElseThrow(() -> new CategoryNotFoundException(id));

        if (bookRepository.existsByCategoryId(id)) {
            throw new CategoryInUseException(id, category.getName());
        }

        categoryRepository.delete(category);
    }

    /** Converts a stored entity into the object the API sends back. */
    /**
     * The account behind the authenticated name.
     *
     * <p>Every method in this class reaches its tenant through here, so the
     * library is always derived from the caller the server authenticated and
     * never from anything the request carried. One place to look also means one
     * place to change if identity ever moves off the username.</p>
     *
     * <p>Callers that need only the tenant id take {@code .getLibrary().getId()}
     * from the result. Reading a lazy proxy's identifier does not initialise it,
     * so those paths need no transaction; {@link #createCategory} is the
     * exception, because it associates the proxy itself with a new row.</p>
     *
     * @param authenticatedUsername the caller's login name
     * @return the caller's account
     * @throws UserNotFoundException if the authenticated name matches no account
     */
    private User authenticatedUser(String authenticatedUsername) {
        return userRepository.findByUsername(authenticatedUsername)
                .orElseThrow(() -> new UserNotFoundException(authenticatedUsername));
    }

    private CategoryResponse toResponse(Category category) {
        return new CategoryResponse(category.getId(), category.getName());
    }
}
