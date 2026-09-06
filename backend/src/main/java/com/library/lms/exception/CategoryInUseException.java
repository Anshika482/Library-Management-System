package com.library.lms.exception;

/**
 * Thrown when a category cannot be deleted because books still point at it.
 *
 * <p>This is not a validation failure and not a missing resource - the request
 * was well formed and the category exists. It is a <b>conflict with the current
 * state of the data</b>, which is why GlobalExceptionHandler answers 409 rather
 * than 400 or 404.</p>
 *
 * <p>The alternative behaviours are both worse. Cascading the delete would
 * silently destroy books, and letting the database's foreign key reject the
 * statement would surface a constraint name to the client. Checking first and
 * throwing this says plainly what happened and leaves every row untouched.</p>
 *
 * <p>The message names the category by id <i>and</i> name, so whoever hits it
 * knows exactly which one to reassign before trying again.</p>
 */
public class CategoryInUseException extends RuntimeException {

    /**
     * @param id   the id of the category that is still referenced
     * @param name the name of that category
     */
    public CategoryInUseException(Long id, String name) {
        super("Category cannot be deleted because books are associated with it: "
                + name + " (id " + id + ")");
    }
}
