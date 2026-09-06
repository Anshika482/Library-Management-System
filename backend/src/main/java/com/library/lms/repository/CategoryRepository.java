package com.library.lms.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.library.lms.entity.Category;

/**
 * Data-access layer for {@link Category}.
 *
 * <p>Almost everything needed is inherited from {@code JpaRepository}:
 * {@code findById} turns a {@code categoryId} into a Category, and
 * {@code findAll(Sort)} backs the list endpoint. Only the duplicate check below
 * needs declaring.</p>
 */
@Repository
public interface CategoryRepository extends JpaRepository<Category, Long> {

    /**
     * Reports whether a category with this name already exists, ignoring case.
     *
     * <p>A derived query - no @Query, no SQL. Spring Data reads the name as
     * {@code exists} + {@code ByName} + {@code IgnoreCase} and writes
     * {@code SELECT count(*) ... WHERE upper(name) = upper(?)}.</p>
     *
     * <p>{@code IgnoreCase} is the important part: it makes "Programming" and
     * "programming" the same shelf, so case variants cannot multiply. Doing it
     * in the query rather than trusting MySQL's case-insensitive collation
     * keeps the behaviour explicit and independent of the database's settings.</p>
     *
     * <p>Returning {@code boolean} rather than the entity says exactly what the
     * caller wants to know - the row itself is never needed, only whether the
     * name is taken.</p>
     */
    boolean existsByNameIgnoreCase(String name);

    /**
     * The same question, but ignoring one category - the one being renamed.
     *
     * <p>Update needs a subtly different check from create. On create, any
     * match at all is a clash. On update, a match against the category's
     * <b>own</b> row is not a clash: renaming "Fiction" to "Fiction" (or
     * fixing its capitalisation) has to be allowed.</p>
     *
     * <p>{@code AndIdNot} is what expresses that. Spring Data derives
     * {@code WHERE upper(name) = upper(?) AND id <> ?}, so the row being edited
     * is excluded from the search and only a <i>different</i> category holding
     * the name is reported.</p>
     *
     * <p>This does not replace {@code existsByNameIgnoreCase} above - create
     * has no id to exclude, and passing null would make the {@code id <> ?}
     * comparison never true, silently disabling the check.</p>
     */
    boolean existsByNameIgnoreCaseAndIdNot(String name, Long id);
}
