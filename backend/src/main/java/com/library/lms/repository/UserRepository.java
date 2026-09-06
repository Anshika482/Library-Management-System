package com.library.lms.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.library.lms.entity.User;

/**
 * Data-access layer for {@link User}.
 *
 * <p>Deliberately empty. Issuing a book needs exactly one thing from the user
 * side - fetch an account by id - and {@code findById} is inherited from
 * {@code JpaRepository}. Lookups by username or email will be needed when
 * authentication arrives; adding them now would be methods nothing calls.</p>
 */
@Repository
public interface UserRepository extends JpaRepository<User, Long> {
}
