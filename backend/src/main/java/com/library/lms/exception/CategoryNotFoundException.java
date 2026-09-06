package com.library.lms.exception;

/**
 * Thrown when a book refers to a category id that does not exist.
 *
 * <p>Necessary because a book now points at a Category row rather than holding
 * a free-text label: a client can send any number as {@code categoryId}, and a
 * number that matches nothing has to be reported clearly rather than silently
 * saving a book with no category.</p>
 *
 * <p>Deliberately a sibling of {@link BookNotFoundException} - same shape, same
 * unchecked base class, and GlobalExceptionHandler maps it to the same 404.</p>
 */
public class CategoryNotFoundException extends RuntimeException {

    /**
     * @param id the category id that was looked up and not found
     */
    public CategoryNotFoundException(Long id) {
        super("Category not found with id: " + id);
    }
}
