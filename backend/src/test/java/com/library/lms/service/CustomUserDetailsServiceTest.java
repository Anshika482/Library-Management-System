package com.library.lms.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import com.library.lms.entity.Role;
import com.library.lms.entity.User;
import com.library.lms.repository.UserRepository;

/**
 * Pins what {@link CustomUserDetailsService} hands Spring Security, and how it
 * finds it.
 *
 * <p>Every login and every authenticated request passes through
 * {@code loadUserByUsername}: the login endpoint to check a password, the JWT
 * filter to rebuild the caller's authorities. The integration tests exercise it
 * only for accounts that exist. Nothing covered an unknown name, a blank one,
 * or the exact shape of what comes back, so a change to how the account is
 * looked up had nothing to hold it to the behaviour it replaced.</p>
 *
 * <p><b>Mockito, not a context.</b> The question is what this class does with
 * what the repository returns, not whether MySQL can run a query. A mocked
 * repository also makes the lookup itself observable: the test can assert
 * which finder was called, with which argument, and that nothing else was.</p>
 */
class CustomUserDetailsServiceTest {

    private static final String USERNAME = "step136-reader";

    private static final String NOT_FOUND = "No account matches the supplied credentials";

    /**
     * Stands in for the stored credential.
     *
     * <p>Deliberately not shaped like a BCrypt hash. This class only checks that
     * the value is passed through untouched; it never verifies a password, so
     * nothing here needs to look like real credential material.</p>
     */
    private static final String STORED_CREDENTIAL = "stored-credential-placeholder";

    private UserRepository userRepository;

    private CustomUserDetailsService service;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        service = new CustomUserDetailsService(userRepository);
    }

    private static User account(String username, Role role) {
        User user = new User();
        user.setUsername(username);
        user.setPassword(STORED_CREDENTIAL);
        user.setRole(role);
        return user;
    }

    // ---------- an account that exists ----------

    @ParameterizedTest
    @EnumSource(Role.class)
    void anExistingAccountIsLoadedWithItsCredentialAndExactlyItsRole(Role role) {
        when(userRepository.findByUsername(USERNAME)).thenReturn(Optional.of(account(USERNAME, role)));

        UserDetails details = service.loadUserByUsername(USERNAME);

        assertThat(details.getUsername()).isEqualTo(USERNAME);
        assertThat(details.getPassword())
                .as("the stored credential reaches Spring Security untouched")
                .isEqualTo(STORED_CREDENTIAL);
        assertThat(details.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .as("one authority, named exactly as the role - SecurityConfig matches on these strings")
                .containsExactly(role.name());
    }

    @Test
    void anExistingAccountIsEnabledAndUnlocked() {
        // The entity has no account-status columns, so every account is active.
        // Pinned here so that adding one later is a visible decision rather than
        // a side effect of how the lookup is written.
        when(userRepository.findByUsername(USERNAME))
                .thenReturn(Optional.of(account(USERNAME, Role.ROLE_MEMBER)));

        UserDetails details = service.loadUserByUsername(USERNAME);

        assertThat(details.isEnabled()).isTrue();
        assertThat(details.isAccountNonLocked()).isTrue();
        assertThat(details.isAccountNonExpired()).isTrue();
        assertThat(details.isCredentialsNonExpired()).isTrue();
    }

    // ---------- how it is found ----------

    @Test
    void theLookupIsTheExactUsernameFinderWithTheNamePassedThroughVerbatim() {
        // Mixed case on purpose. The service must not trim or lower-case the
        // name: whether two spellings match is the column collation's decision,
        // not this class's. And the finder must be the only repository call.
        String asTyped = "Step136-MixedCase";
        when(userRepository.findByUsername(asTyped))
                .thenReturn(Optional.of(account(asTyped, Role.ROLE_LIBRARIAN)));

        service.loadUserByUsername(asTyped);

        verify(userRepository).findByUsername(asTyped);
        verifyNoMoreInteractions(userRepository);
    }

    // ---------- an account that does not exist ----------

    @Test
    void anUnknownUsernameIsRefusedWithTheFixedMessage() {
        when(userRepository.findByUsername("step136-nobody")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.loadUserByUsername("step136-nobody"))
                .isInstanceOf(UsernameNotFoundException.class)
                .hasMessage(NOT_FOUND)
                .as("the refusal must not echo the name it could not find")
                .hasMessageNotContaining("step136-nobody");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   "})
    void aBlankUsernameIsRefusedWithoutTouchingTheDatabase(String username) {
        // Same exception and the same message as a genuine miss, so a caller
        // cannot tell "blank" from "unknown" - and no query is spent on a name
        // that cannot match.
        assertThatThrownBy(() -> service.loadUserByUsername(username))
                .isInstanceOf(UsernameNotFoundException.class)
                .hasMessage(NOT_FOUND);

        verifyNoInteractions(userRepository);
    }
}
