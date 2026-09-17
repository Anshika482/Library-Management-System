package com.library.lms.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.library.lms.entity.Library;

/**
 * Data-access layer for {@link Library}.
 *
 * <p>{@code findById}, {@code findAll} and {@code save} arrive from
 * {@code JpaRepository}. The two methods declared here both look a library up
 * by its name, which is unique across the whole system.</p>
 */
@Repository
public interface LibraryRepository extends JpaRepository<Library, Long> {

    /**
     * Finds the library with this name.
     *
     * <p>The column is UNIQUE, so at most one row can match and an
     * {@link Optional} is the honest return type. Spring Data derives the query
     * from the method name; there is no implementation to keep in step.</p>
     *
     * @param name the library name to look for
     * @return the matching library, or empty if there is none
     */
    Optional<Library> findByName(String name);

    /**
     * Whether any library already has this name, ignoring case.
     *
     * <p>Checked before a library is created, so a clash is answered with a
     * clear 400 rather than a constraint violation. Case is ignored explicitly
     * rather than left to the column's collation - the same choice
     * {@code CategoryRepository} makes - because "Central Library" and
     * "central library" are one library to anyone reading the name. The unique
     * index still has the final word, for two requests that pass this check at
     * the same moment.</p>
     *
     * @param name the name to look for, already trimmed
     * @return true if a library with that name exists
     */
    boolean existsByNameIgnoreCase(String name);
}
