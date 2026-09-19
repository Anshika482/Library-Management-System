package com.library.lms.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.library.lms.entity.Library;
import com.library.lms.entity.Role;
import com.library.lms.entity.User;
import com.library.lms.exception.UserDirectoryAccessDeniedException;
import com.library.lms.repository.UserRepository;

/**
 * The user directory's second lock, on its own.
 *
 * <p>The filter chain already keeps members away from {@code /api/users}, so an
 * HTTP test cannot show that {@link UserService} would refuse them too: the
 * request never gets that far. This calls the service directly, as a member,
 * with no filter chain in front of it - which is the only way to prove the
 * service's own rule still holds if the chain's is ever loosened.</p>
 */
class UserDirectoryServiceTest {

    private final UserRepository users = mock(UserRepository.class);

    private final UserService service = new UserService(users, mock(PasswordEncoder.class),
            mock(RefreshTokenService.class));

    private void givenCaller(Role role) {
        Library library = new Library();
        library.setId(1L);

        User caller = new User();
        caller.setId(10L);
        caller.setUsername("caller");
        caller.setRole(role);
        caller.setLibrary(library);

        when(users.findByUsername("caller")).thenReturn(Optional.of(caller));
    }

    @Test
    @SuppressWarnings("unchecked")
    void aMemberCannotListTheDirectoryEvenWithoutTheFilterChain() {
        givenCaller(Role.ROLE_MEMBER);

        assertThatThrownBy(() -> service.listUsers(0, 10, "id", "asc", null, null, null, null, "caller"))
                .isInstanceOf(UserDirectoryAccessDeniedException.class);

        verify(users, never()).findAll(any(Specification.class), any(Pageable.class));
    }

    @Test
    void aMemberCannotViewAnAccountEvenWithoutTheFilterChain() {
        givenCaller(Role.ROLE_MEMBER);

        assertThatThrownBy(() -> service.getUser(20L, "caller"))
                .isInstanceOf(UserDirectoryAccessDeniedException.class);

        verify(users, never()).findByIdAndLibraryId(anyLong(), anyLong());
    }

    @Test
    void aLibrarianAskingForStaffIsRefusedBeforeAnyQuery() {
        givenCaller(Role.ROLE_LIBRARIAN);

        for (Role staff : new Role[] {Role.ROLE_ADMIN, Role.ROLE_LIBRARIAN}) {
            assertThatThrownBy(() -> service.listUsers(0, 10, "id", "asc", null, staff, null, null, "caller"))
                    .as(staff.name())
                    .isInstanceOf(UserDirectoryAccessDeniedException.class);
        }
    }

    @Test
    void theRefusalCarriesNoDetail() {
        assertThat(new UserDirectoryAccessDeniedException().getMessage()).isEqualTo("Access denied");
    }
}
