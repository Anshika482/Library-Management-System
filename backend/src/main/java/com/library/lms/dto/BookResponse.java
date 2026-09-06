package com.library.lms.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * What the API sends back to a client describing a book.
 *
 * <p>The counterpart to {@link BookRequest}. Where the request is what comes
 * in, this is what goes out, and the difference between them is deliberate:
 * this one carries the {@code id}, because by the time a book is being returned
 * the database has assigned one.</p>
 *
 * <p>There are no validation annotations here, and that is correct - validation
 * guards data arriving from outside. Anything we send has already come from our
 * own database, so there is nothing to check.</p>
 *
 * <p>The reason for returning this instead of the entity is control: the
 * response stays stable even if the Book entity later grows fields we would
 * rather not publish. Switching the controller over to it is a later step.</p>
 */
@Getter
@Setter
@ToString
@NoArgsConstructor
@AllArgsConstructor
public class BookResponse {

    /** Assigned by the database, so it is present on the way out but never on the way in. */
    private Long id;

    private String title;

    private String author;

    private String isbn;

    /**
     * The category's id and name, flattened out of the Category entity.
     *
     * <p>Two plain fields rather than a nested Category object. Returning the
     * entity would leak the database structure into the API and mean every
     * future column added to Category silently appears in every book response.
     * The id lets a client refer back to the category; the name is what a
     * person reads.</p>
     *
     * <p>Both are null for a book with no category, which includes every book
     * saved before categories became a table.</p>
     */
    private Long categoryId;

    private String categoryName;

    private Integer totalCopies;

    private Integer availableCopies;
}
