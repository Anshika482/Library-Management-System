package com.library.lms.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.library.lms.dto.AdminPasswordResetRequest;
import com.library.lms.entity.Library;
import com.library.lms.entity.Role;
import com.library.lms.entity.User;
import com.library.lms.exception.PasswordResetNotAllowedException;
import com.library.lms.exception.SelfPasswordResetException;
import com.library.lms.exception.UserNotFoundException;
import com.library.lms.repository.UserRepository;

/**
 * The staff password reset's rules inside {@link UserService}, with no filter
 * chain in front.
 *
 * <p>Two things only a unit test can show. First, that the service refuses a
 * member on its own - over HTTP the filter chain answers first, so a loosened
 * chain would go unnoticed without this. Second, that a refusal leaves
 * everything as it was: no password encoded, nothing saved, no session revoked,
 * no login block cleared. Mocks make "nothing happened" a checkable fact.</p>
 */
class PasswordResetServiceTest {

    private static final long LIBRARY_ID = 1L;

    private static final String NEW_PASSWORD = "unit-test-new-password";

    private final UserRepository users = mock(UserRepository.class);

    private final PasswordEncoder encoder = mock(PasswordEncoder.class);

    private final RefreshTokenService refreshTokens = mock(RefreshTokenService.class);

    private final LoginAttemptService loginAttempts = mock(LoginAttemptService.class);

    private final UserService service = new UserService(users, encoder, refreshTokens, loginAttempts,
            mock(AuditService.class));

    private Library library;

    @BeforeEach
    void oneLibrary() {
        library = new Library();
        library.setId(LIBRARY_ID);
    }

    private User account(long id, String username, Role role) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        user.setRole(role);
        user.setLibrary(library);
        user.setPassword("$2a$10$stored-hash-for-" + username);
        return user;
    }

    private User caller(long id, Role role) {
        User caller = account(id, "caller", role);
        when(users.findByUsername("caller")).thenReturn(Optional.of(caller));
        return caller;
    }

    private void inLibrary(User target) {
        when(users.findByIdAndLibraryId(target.getId(), LIBRARY_ID)).thenReturn(Optional.of(target));
    }

    private static AdminPasswordResetRequest request() {
        return new AdminPasswordResetRequest(NEW_PASSWORD);
    }

    private void assertNothingChanged() {
        verify(encoder, never()).encode(anyString());
        verify(users, never()).save(any());
        verifyNoInteractions(refreshTokens, loginAttempts);
    }

    // ---------- refusals leave everything as it was ----------

    @Test
    void aMemberIsRefusedByTheServiceItselfBeforeAnyLookup() {
        caller(10L, Role.ROLE_MEMBER);

        assertThatThrownBy(() -> service.resetPassword(20L, request(), "caller"))
                .isInstanceOf(PasswordResetNotAllowedException.class);

        verify(users, never()).findByIdAndLibraryId(anyLong(), anyLong());
        assertNothingChanged();
    }

    @Test
    void aLibrarianNamingStaffIsRefused() {
        caller(10L, Role.ROLE_LIBRARIAN);

        for (User staff : new User[] {account(20L, "admin", Role.ROLE_ADMIN),
                account(21L, "librarian", Role.ROLE_LIBRARIAN)}) {
            inLibrary(staff);

            assertThatThrownBy(() -> service.resetPassword(staff.getId(), request(), "caller"))
                    .as(staff.getRole().name())
                    .isInstanceOf(PasswordResetNotAllowedException.class);
        }

        assertNothingChanged();
    }

    @Test
    void aLibrarianNamingThemselvesIsRefusedAsStaff() {
        User librarian = caller(10L, Role.ROLE_LIBRARIAN);
        inLibrary(librarian);

        assertThatThrownBy(() -> service.resetPassword(10L, request(), "caller"))
                .isInstanceOf(PasswordResetNotAllowedException.class);

        assertNothingChanged();
    }

    @Test
    void anAdministratorNamingThemselvesIsRefused() {
        User admin = caller(10L, Role.ROLE_ADMIN);
        inLibrary(admin);

        assertThatThrownBy(() -> service.resetPassword(10L, request(), "caller"))
                .isInstanceOf(SelfPasswordResetException.class)
                .hasMessageContaining("/api/auth/password");

        assertNothingChanged();
    }

    @Test
    void anAccountOutsideTheCallersLibraryIsNotFound() {
        caller(10L, Role.ROLE_ADMIN);
        when(users.findByIdAndLibraryId(99L, LIBRARY_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resetPassword(99L, request(), "caller"))
                .isInstanceOf(UserNotFoundException.class);

        verify(users, never()).findById(anyLong());
        assertNothingChanged();
    }

    // ---------- a reset that goes through ----------

    @Test
    void aResetEncodesSavesRevokesAndClearsTheBlockInThatOrder() {
        caller(10L, Role.ROLE_ADMIN);
        User member = account(20L, "forgetful-member", Role.ROLE_MEMBER);
        inLibrary(member);
        when(encoder.encode(NEW_PASSWORD)).thenReturn("$2a$10$fresh-hash");

        service.resetPassword(20L, request(), "caller");

        InOrder order = inOrder(encoder, users, refreshTokens, loginAttempts);
        order.verify(encoder).encode(NEW_PASSWORD);
        order.verify(users).save(member);
        order.verify(refreshTokens).revokeAllFor(member);
        order.verify(loginAttempts).reset("forgetful-member");

        assertThat(member.getPassword()).as("the encoder's output, never the password").isEqualTo("$2a$10$fresh-hash");
    }

    @Test
    void aLibrarianResetsAMember() {
        caller(10L, Role.ROLE_LIBRARIAN);
        User member = account(20L, "member", Role.ROLE_MEMBER);
        inLibrary(member);
        when(encoder.encode(NEW_PASSWORD)).thenReturn("$2a$10$fresh-hash");

        service.resetPassword(20L, request(), "caller");

        verify(refreshTokens).revokeAllFor(member);
        verify(loginAttempts).reset("member");
    }

    @Test
    void theRequestNeverPrintsThePassword() {
        assertThat(request().toString()).doesNotContain(NEW_PASSWORD);
    }
}
