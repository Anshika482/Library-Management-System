package com.library.lms.service;

import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import com.library.lms.entity.User;
import com.library.lms.repository.UserRepository;

/**
 * Teaches Spring Security how to find one of our users.
 *
 * <p>Spring Security has no idea what a {@link User} is or that a MySQL table
 * called {@code users} exists. It only knows the {@link UserDetailsService}
 * contract: hand me a name, give me back credentials and authorities. This
 * class is the whole of that translation, and it is the point where the
 * framework stops being generic and starts using our data.</p>
 *
 * <p>Declaring it as a bean has a second effect worth knowing. Spring Boot's
 * {@code UserDetailsServiceAutoConfiguration} creates a throwaway in memory
 * user and prints a generated password at startup, and it backs off as soon as
 * a UserDetailsService of our own exists. From this class onward that default
 * account is gone and the database is the only source of accounts.</p>
 *
 * <p>Spring Security calls {@link #loadUserByUsername(String)} whenever it
 * needs to establish who a caller is. At login, the authentication manager
 * uses it to fetch the account so the submitted password can be checked
 * against the stored one. On each authenticated request, the JWT filter uses
 * it to reload the account named in the token, so the caller's authority comes
 * from the database rather than from the token.</p>
 *
 * <p>The account is found through {@link UserRepository#findByUsername(String)}.
 * The {@link UserDetails} returned carries the stored (hashed) password and a
 * single authority named after the user's role.</p>
 */
@Service
public class CustomUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    public CustomUserDetailsService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /**
     * Looks up one account by its login name.
     *
     * <p>{@code username} is the login identifier because the entity says so:
     * the field is documented as the "Login name", and the column is NOT NULL
     * and UNIQUE. Email is also unique and could serve the same purpose, but
     * picking it would be inventing a convention the project does not have.</p>
     *
     * <p>The lookup is {@link UserRepository#findByUsername(String)}, the same
     * finder the book, category and transaction services use to resolve the
     * caller. It is a derived query - Spring Data builds
     * {@code WHERE username = ?} from the method name - so it runs against the
     * unique index and loads at most one row. The name is passed through
     * untouched, neither trimmed nor lower-cased, so whether two spellings match
     * is decided by the column's collation rather than by this class.</p>
     *
     * <p>A blank or missing name is refused before the repository is called,
     * so such a request never reaches the database. It fails with the same
     * exception and the same message as a name that simply does not exist, so
     * the two cannot be told apart from outside.</p>
     *
     * <p>The returned object carries the stored BCrypt hash because that is
     * what the contract requires: Spring Security compares a submitted password
     * against it using the encoder configured elsewhere. It is passed straight
     * from the entity to the builder and is never logged, printed, put in an
     * exception message or returned from an endpoint. The failure message names
     * no username either, so a caller cannot use timing or text to discover
     * which accounts exist.</p>
     *
     * @param username the login name being authenticated
     * @return the account in the form Spring Security understands
     * @throws UsernameNotFoundException if no account has that login name
     */
    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        if (username == null || username.isBlank()) {
            throw new UsernameNotFoundException("No account matches the supplied credentials");
        }

        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("No account matches the supplied credentials"));

        // Two different classes are called User here: ours above, Spring
        // Security's below. Java cannot alias an import, so the framework one is
        // spelled out in full rather than shadowing the entity.
        return org.springframework.security.core.userdetails.User
                .withUsername(user.getUsername())
                .password(user.getPassword())
                .authorities(new SimpleGrantedAuthority(user.getRole().name()))
                .build();
    }
}
