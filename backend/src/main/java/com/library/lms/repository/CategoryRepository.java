package com.library.lms.repository;

import java.util.List;
import java.util.Optional;

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
    /**
     * Every category owned by one library, oldest first.
     *
     * <p>The tenant filter is part of the query, not something applied to the
     * results afterwards. Fetching all rows and discarding the ones that belong
     * to other libraries would read data the caller may not see and would get
     * slower with every library added.</p>
     *
     * <p>{@code OrderByIdAsc} keeps the ordering the listing endpoint already
     * had, which the previous {@code findAll(Sort.by(ASC, "id"))} supplied.</p>
     *
     * @param libraryId the owning library
     * @return that library's categories, ordered by id
     */
    List<Category> findByLibraryIdOrderByIdAsc(Long libraryId);

    /**
     * One category, but only if it belongs to this library.
     *
     * <p>Scoping the lookup rather than loading the row and checking afterwards
     * is what keeps the two failure cases identical. A category in another
     * library and a category that was never created both return empty here, so
     * the caller cannot tell them apart - and the row is never read at all,
     * which means a refused update or delete cannot act on data it was refused.</p>
     *
     * @param id        the category wanted
     * @param libraryId the caller's library
     * @return the category, or empty if it is missing or belongs elsewhere
     */
    Optional<Category> findByIdAndLibraryId(Long id, Long libraryId);

    /**
     * Whether this library already has a category with this name.
     *
     * <p>Scoped, because a name is only a duplicate within one library. Two
     * libraries both wanting "Fiction" is the normal case, not a clash, and the
     * global check this replaces refused the second one.</p>
     *
     * <p>{@code IgnoreCase} matches the column's {@code utf8mb4_0900_ai_ci}
     * collation, so this check and the {@code uk_categories_library_name}
     * constraint agree on what counts as the same name.</p>
     *
     * @param libraryId the caller's library
     * @param name      the trimmed name being claimed
     * @return true if that library already uses the name
     */
    boolean existsByLibraryIdAndNameIgnoreCase(Long libraryId, String name);

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
     * <p>This does not replace {@code existsByLibraryIdAndNameIgnoreCase}
     * above - create has no id to exclude, and passing null would make the
     * {@code id <> ?} comparison never true, silently disabling the check.</p>
     */
    boolean existsByLibraryIdAndNameIgnoreCaseAndIdNot(Long libraryId, String name, Long id);
}
