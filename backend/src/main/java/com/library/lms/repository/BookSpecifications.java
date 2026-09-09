package com.library.lms.repository;

import org.springframework.data.jpa.domain.Specification;

import com.library.lms.entity.Book;

/**
 * Reusable query fragments for finding books.
 *
 * <p>A {@link Specification} is one piece of a WHERE clause, built with the JPA
 * Criteria API instead of a method name. Pieces combine with {@code and} /
 * {@code or}, and Spring Data turns the result into a single SQL statement -
 * so filtering, counting and paging all still happen in the database.</p>
 *
 * <p><b>Why not derived query methods.</b> The condition this API needs is</p>
 *
 * <pre>category = ? AND (title LIKE ? OR author LIKE ? OR isbn LIKE ?)</pre>
 *
 * <p>and a derived method name cannot express those brackets - Spring Data
 * binds {@code And} more tightly than {@code Or}, so
 * {@code findByCategoryIdAndTitleContainingOrAuthorContaining} actually means
 * {@code (category AND title) OR author}, which is wrong and silently so. The
 * only way to fix it with derived queries is to repeat the category on every
 * branch, giving an unreadable method name that grows worse with each new
 * filter. Specifications keep the grouping explicit and each filter separate.</p>
 *
 * <p>The class is final with a private constructor because it is a holder for
 * static factory methods; there is nothing to instantiate.</p>
 */
public final class BookSpecifications {

    private BookSpecifications() {
        // utility class
    }

    /**
     * Matches every book - the neutral starting point for building a filter.
     *
     * <p>{@code conjunction()} is SQL's {@code 1=1}. Starting from it means the
     * caller can add optional filters with plain {@code and(...)} calls without
     * a null check before each one, and "no filters at all" needs no special
     * case: the query simply matches everything.</p>
     */
    public static Specification<Book> always() {
        return (root, query, criteriaBuilder) -> criteriaBuilder.conjunction();
    }

    /**
     * Restricts results to one category.
     *
     * <p>Navigates {@code category.id} through the entity relationship, which
     * Hibernate reads straight from the {@code category_id} foreign key without
     * needing a join. It deliberately does <b>not</b> touch the legacy
     * {@code books.category} text column - that column is a leftover from before
     * categories were normalised and can drift out of date if a category is
     * renamed.</p>
     */
    /**
     * Restricts a query to one library's shelves.
     *
     * <p>This is the tenant filter, and it is the one predicate that must be
     * present on every book query. The others narrow a result set the caller is
     * already entitled to see; this one decides entitlement. Composing it in the
     * service rather than leaving it optional means paging, keyword search and
     * category filtering are all scoped by the same clause, in the database,
     * rather than each remembering to filter for itself.</p>
     *
     * @param libraryId the caller's library
     * @return a predicate matching only that library's books
     */
    public static Specification<Book> belongsToLibrary(Long libraryId) {
        return (root, query, criteriaBuilder) ->
                criteriaBuilder.equal(root.get("library").get("id"), libraryId);
    }

    public static Specification<Book> hasCategory(Long categoryId) {
        return (root, query, criteriaBuilder) ->
                criteriaBuilder.equal(root.get("category").get("id"), categoryId);
    }

    /**
     * Matches books whose title, author or ISBN contains the keyword.
     *
     * <p>The three comparisons are wrapped in a single {@code or(...)}, so the
     * whole group is emitted as one bracketed expression. That is what keeps it
     * correct when this specification is later {@code and}-ed with a category
     * filter: the brackets are part of this fragment, not something the caller
     * has to remember.</p>
     *
     * <p>Case-insensitivity is done by lowering both sides, matching the
     * behaviour the search endpoint has always had. The pattern is passed as a
     * bound parameter by the Criteria API, never concatenated into SQL.</p>
     */
    public static Specification<Book> matchesKeyword(String keyword) {
        String pattern = "%" + keyword.toLowerCase() + "%";

        return (root, query, criteriaBuilder) -> criteriaBuilder.or(
                criteriaBuilder.like(criteriaBuilder.lower(root.get("title")), pattern),
                criteriaBuilder.like(criteriaBuilder.lower(root.get("author")), pattern),
                criteriaBuilder.like(criteriaBuilder.lower(root.get("isbn")), pattern));
    }
}
