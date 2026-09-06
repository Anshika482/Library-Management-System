package com.library.lms.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * What a client is allowed to send when creating or updating a book.
 *
 * <p>This is a <b>DTO</b> - a Data Transfer Object. It looks similar to
 * {@link com.library.lms.entity.Book}, but it exists for a different reason:
 * Book describes a database row, while BookRequest describes an incoming HTTP
 * body. Keeping them apart buys two things:</p>
 * <ul>
 *   <li>The client cannot set fields it has no business setting. Notice there
 *       is no {@code id} here - the database assigns that, not the caller.</li>
 *   <li>Validation rules live on the request rather than on the table, so a bad
 *       request is rejected at the edge of the application instead of failing
 *       later as a database error.</li>
 * </ul>
 *
 * <p>The annotations below are <b>Bean Validation</b> (the jakarta.validation
 * package). They only describe the rules; the checking is done by Hibernate
 * Validator once a controller marks a parameter with {@code @Valid}. Wiring
 * that into the controller is a later step, so nothing enforces these rules
 * yet.</p>
 *
 * <p>The maximum lengths deliberately match the {@code @Column(length = ...)}
 * values on the Book entity, so a request that passes validation can always
 * fit in the table.</p>
 */
@Getter
@Setter
@ToString
@NoArgsConstructor      // Jackson creates an empty object, then calls the setters
@AllArgsConstructor
public class BookRequest {

    /**
     * {@code @NotBlank} means "present, and not just spaces". It is stricter
     * than @NotNull, which would happily accept an empty string.
     */
    @NotBlank(message = "Title is required")
    @Size(max = 200, message = "Title must not exceed 200 characters")
    private String title;

    @NotBlank(message = "Author is required")
    @Size(max = 150, message = "Author must not exceed 150 characters")
    private String author;

    @NotBlank(message = "ISBN is required")
    @Size(max = 20, message = "ISBN must not exceed 20 characters")
    private String isbn;

    /**
     * Which category this book belongs to, given by id.
     *
     * <p>The client sends a number, not a name. That is the point of
     * normalising categories: the shelf already exists as a row, and the book
     * simply points at it, so nobody can invent "Programing" by typo.</p>
     *
     * <p>Still optional - a book with no category is allowed, exactly as
     * before - so there is no {@code @NotNull}. A number that matches no
     * category is rejected by the service with a clear 404 rather than by a
     * validation rule here, because whether the row exists is a database
     * question, not something an annotation can check.</p>
     */
    private Long categoryId;

    /**
     * {@code @NotNull} is the right choice for a number: an Integer has no
     * "blank" state, it is either provided or missing. {@code @Min(0)} then
     * rejects negative counts, which would make no sense for copies of a book.
     */
    @NotNull(message = "Total copies is required")
    @Min(value = 0, message = "Total copies must be 0 or greater")
    private Integer totalCopies;

    @NotNull(message = "Available copies is required")
    @Min(value = 0, message = "Available copies must be 0 or greater")
    private Integer availableCopies;
}
