package com.library.lms.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.library.lms.dto.CategoryRequest;
import com.library.lms.dto.CategoryResponse;
import com.library.lms.dto.PagedResponse;
import com.library.lms.service.CategoryService;

import jakarta.validation.Valid;

/**
 * REST endpoints for reading categories.
 *
 * <p>One endpoint, and it exists to solve a real gap: since a book is created
 * by sending a {@code categoryId}, a client previously had no way to discover
 * which ids are valid. This is that lookup.</p>
 *
 * <p>Read-only by design for this step - no POST, PUT or DELETE. Structured
 * exactly like {@link BookController}: a shared {@code @RequestMapping} prefix,
 * constructor injection of the service, and DTOs rather than entities crossing
 * the boundary.</p>
 */
@RestController
@RequestMapping("/api/categories")
public class CategoryController {

    private final CategoryService categoryService;

    public CategoryController(CategoryService categoryService) {
        this.categoryService = categoryService;
    }

    /**
     * GET /api/categories - one page of the caller's library's categories.
     *
     * <p>Paged like the book list: {@code page} from 0, {@code size} from 1 to
     * 50, ordered by {@code id} or {@code name} in either direction, and by
     * default the first ten by id ascending - the order this list always had.</p>
     *
     * <p>Always 200 OK. An empty library of categories is a normal state, not
     * an error, so it answers with an empty page rather than a 404.</p>
     */
    @GetMapping
    public ResponseEntity<PagedResponse<CategoryResponse>> getAllCategories(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "id") String sortBy,
            @RequestParam(defaultValue = "asc") String direction,
            Authentication authentication) {
        PagedResponse<CategoryResponse> categories =
                categoryService.getAllCategories(page, size, sortBy, direction, authentication.getName());
        return ResponseEntity.ok(categories);
    }

    /**
     * POST /api/categories - adds a new category.
     *
     * <p>{@code @Valid} runs the rules on {@link CategoryRequest} before this
     * method body starts, so a blank or over-long name is turned into a 400 by
     * the existing validation handler - no extra handling is written here.</p>
     *
     * <p>Answers <b>201 CREATED</b> rather than 200, because a resource now
     * exists that did not before. The returned {@link CategoryResponse} carries
     * the id the database generated, which is the value a client then sends as
     * {@code categoryId} when creating a book.</p>
     */
    @PostMapping
    public ResponseEntity<CategoryResponse> createCategory(@Valid @RequestBody CategoryRequest categoryRequest,
                                                           Authentication authentication) {
        CategoryResponse createdCategory =
                categoryService.createCategory(categoryRequest, authentication.getName());
        return ResponseEntity.status(HttpStatus.CREATED).body(createdCategory);
    }

    /**
     * PUT /api/categories/{id} - renames an existing category.
     *
     * <p>The id comes from the URL and the new name from the body, validated by
     * the same {@code @Valid} rules as POST. Because CategoryRequest has no id
     * field, the URL is necessarily the authority on which row is edited.</p>
     *
     * <p>Answers <b>200 OK</b>, not 201: nothing new was created, an existing
     * resource was modified. An unknown id becomes a 404 and a name already
     * held by another category becomes a 400, both through the existing
     * exception handlers.</p>
     */
    @PutMapping("/{id}")
    public ResponseEntity<CategoryResponse> updateCategory(@PathVariable Long id,
                                                           @Valid @RequestBody CategoryRequest categoryRequest,
                                                           Authentication authentication) {
        CategoryResponse updatedCategory =
                categoryService.updateCategory(id, categoryRequest, authentication.getName());
        return ResponseEntity.ok(updatedCategory);
    }

    /**
     * DELETE /api/categories/{id} - removes a category that nothing is using.
     *
     * <p>Answers <b>204 NO CONTENT</b> on success: the category is gone and
     * there is deliberately nothing to send back, which is why the type
     * parameter is {@code Void} and the body is left empty.</p>
     *
     * <p>Two failures are possible and both are handled elsewhere: an unknown
     * id gives 404, and a category still referenced by books gives <b>409
     * CONFLICT</b>. The 409 is not a rejection of the request's form - it is
     * correct, and the category exists - but the current data will not allow
     * it. Those books must be moved to another category first.</p>
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteCategory(@PathVariable Long id, Authentication authentication) {
        categoryService.deleteCategory(id, authentication.getName());
        return ResponseEntity.noContent().build();
    }
}
