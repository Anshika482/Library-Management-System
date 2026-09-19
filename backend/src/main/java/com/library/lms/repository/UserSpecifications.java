package com.library.lms.repository;

import org.springframework.data.jpa.domain.Specification;

import com.library.lms.entity.Role;
import com.library.lms.entity.User;

/**
 * Reusable query fragments for the user directory.
 *
 * <p>The same approach as {@link BookSpecifications}: each filter is one piece
 * of a WHERE clause, the pieces are combined with {@code and}, and Spring Data
 * turns the result into a single statement, so filtering, counting and paging
 * all happen in the database.</p>
 *
 * <p>The class is final with a private constructor because it is a holder for
 * static factory methods; there is nothing to instantiate.</p>
 */
public final class UserSpecifications {

    private UserSpecifications() {
        // utility class
    }

    /**
     * Restricts a query to one library's accounts.
     *
     * <p>This is the tenant filter, and the one predicate that must be present
     * on every directory query. The others narrow a result the caller is
     * already entitled to see; this one decides entitlement. The service
     * composes it first, so paging, search and every other filter are scoped
     * by the same clause, in the database.</p>
     *
     * @param libraryId the caller's library
     * @return a predicate matching only that library's accounts
     */
    public static Specification<User> belongsToLibrary(Long libraryId) {
        return (root, query, criteriaBuilder) ->
                criteriaBuilder.equal(root.get("library").get("id"), libraryId);
    }

    /** Restricts results to one role. */
    public static Specification<User> hasRole(Role role) {
        return (root, query, criteriaBuilder) -> criteriaBuilder.equal(root.get("role"), role);
    }

    /** Restricts results to enabled, or to disabled, accounts. */
    public static Specification<User> isEnabled(boolean enabled) {
        return (root, query, criteriaBuilder) -> criteriaBuilder.equal(root.get("enabled"), enabled);
    }

    /** Restricts results to unlocked, or to locked, accounts. */
    public static Specification<User> isAccountNonLocked(boolean accountNonLocked) {
        return (root, query, criteriaBuilder) ->
                criteriaBuilder.equal(root.get("accountNonLocked"), accountNonLocked);
    }

    /**
     * Matches accounts whose username or email contains the keyword.
     *
     * <p>The two comparisons are wrapped in one {@code or(...)}, so the group is
     * emitted as a single bracketed expression and stays correct when it is
     * {@code and}-ed with the library and role filters - which is exactly the
     * case where getting the brackets wrong would let a search reach another
     * library's accounts.</p>
     *
     * <p>Case-insensitive, by lowering both sides, as the book search is. The
     * pattern is a bound parameter, never concatenated into SQL. {@code %} and
     * {@code _} in the keyword keep their LIKE meaning, as they do in the book
     * search; they can widen a search within the caller's library, never beyond
     * it.</p>
     */
    public static Specification<User> matchesKeyword(String keyword) {
        String pattern = "%" + keyword.toLowerCase() + "%";

        return (root, query, criteriaBuilder) -> criteriaBuilder.or(
                criteriaBuilder.like(criteriaBuilder.lower(root.get("username")), pattern),
                criteriaBuilder.like(criteriaBuilder.lower(root.get("email")), pattern));
    }
}
