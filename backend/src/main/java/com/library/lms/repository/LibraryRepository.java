package com.library.lms.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.library.lms.entity.Library;

/**
 * Data-access layer for {@link Library}.
 *
 * <p>One declared method, for the same reason {@link UserRepository} carried
 * none until authentication needed one: a query nothing calls is a query nobody
 * maintains. {@code findById}, {@code findAll} and {@code save} arrive from
 * {@code JpaRepository} and cover everything else the coming steps are likely
 * to want.</p>
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
}
