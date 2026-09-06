package com.library.lms.service;

import org.springframework.data.domain.Example;
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
 * <p>Nothing calls this yet. There is no login endpoint and the filter chain
 * still permits every request; this step only supplies the lookup that
 * authentication will need.</p>
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
     * <p>The lookup goes through Query By Example rather than a
     * {@code findByUsername} method, because {@link UserRepository} declares no
     * custom queries and this step may not add one. A probe carrying only the
     * username still becomes a single {@code WHERE username = ?} against the
     * unique index; no table is loaded into memory. See the class note in the
     * step report for why a derived query is the better long term shape.</p>
     *
     * <p>The blank check is not defensive noise. Query By Example ignores null
     * fields, so an empty probe would match every row and turn a missing
     * username into "too many results" rather than "not found".</p>
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

        User probe = new User();
        probe.setUsername(username);

        User user = userRepository.findOne(Example.of(probe))
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
