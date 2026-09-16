package com.library.lms.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.library.lms.entity.User;

/**
 * Data-access layer for {@link User}.
 *
 * <p>{@code findById} is inherited from {@code JpaRepository}. The one method
 * declared here looks an account up by its login name, which is what the code
 * needs now that identity comes from an authenticated principal rather than
 * from an id in a request body.</p>
 */
@Repository
public interface UserRepository extends JpaRepository<User, Long> {

    /**
     * Finds the account with this login name.
     *
     * <p>The column is UNIQUE, so at most one row can match and an
     * {@link Optional} is the honest return type. Spring Data derives the query
     * from the method name; there is no implementation to keep in step.</p>
     *
     * @param username the login name to look for
     * @return the matching account, or empty if there is none
     */
    Optional<User> findByUsername(String username);

    /**
     * Finds one account by id, but only inside the given library.
     *
     * <p>The library condition is what keeps an administrator inside their own
     * tenant. Asking for an id that belongs to another library returns empty,
     * exactly as an id that belongs to nobody does, so the caller cannot tell
     * the two apart.</p>
     *
     * @param id        the account being looked up
     * @param libraryId the library the caller belongs to
     * @return the account, or empty if it is missing or not theirs
     */
    Optional<User> findByIdAndLibraryId(Long id, Long libraryId);
}
