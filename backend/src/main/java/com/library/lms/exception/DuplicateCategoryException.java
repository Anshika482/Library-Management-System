package com.library.lms.exception;

/**
 * Thrown when a category is created under a name that already exists.
 *
 * <p>The whole point of giving categories their own table was to store each
 * shelf name once. Without this check a client could create "Programming",
 * "programming" and " PROGRAMMING " as three separate rows, and the
 * normalisation would be undone by the first user with an inconsistent
 * keyboard.</p>
 *
 * <p>A sibling of {@link DuplicateIsbnException} - same unchecked base class,
 * same idea, and GlobalExceptionHandler maps it to the same 400.</p>
 */
public class DuplicateCategoryException extends RuntimeException {

    /**
     * @param name the category name that is already taken
     */
    public DuplicateCategoryException(String name) {
        super("Category already exists: " + name);
    }
}
