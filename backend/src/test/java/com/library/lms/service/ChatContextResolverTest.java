package com.library.lms.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.library.lms.entity.Library;
import com.library.lms.entity.Role;
import com.library.lms.entity.User;
import com.library.lms.exception.UserNotFoundException;
import com.library.lms.repository.UserRepository;

/**
 * Where the assistant's context comes from.
 *
 * <p>This is the whole of library isolation for a chat answer: the library, the
 * account and the role are read from the caller's own record, and there is no
 * parameter here a caller could influence beyond the name they authenticated
 * as.</p>
 */
class ChatContextResolverTest {

    private final UserRepository userRepository = mock(UserRepository.class);

    private final ChatContextResolver resolver = new ChatContextResolver(userRepository);

    private Library library;
    private User member;

    @BeforeEach
    void fixtures() {
        library = new Library();
        library.setId(7L);
        library.setName("Central Library");

        member = new User();
        member.setId(21L);
        member.setUsername("member");
        member.setRole(Role.ROLE_MEMBER);
        member.setLibrary(library);

        when(userRepository.findByUsername("member")).thenReturn(Optional.of(member));
    }

    @Test
    void theContextIsBuiltFromTheCallersOwnAccount() {
        ChatContext context = resolver.resolve("member");

        assertThat(context.libraryId()).isEqualTo(7L);
        assertThat(context.libraryName()).isEqualTo("Central Library");
        assertThat(context.userId()).isEqualTo(21L);
        assertThat(context.role()).isEqualTo(Role.ROLE_MEMBER);
    }

    @Test
    void theRoleTravelsSoAnAssistantCanDecideWhatToSay() {
        member.setRole(Role.ROLE_LIBRARIAN);

        assertThat(resolver.resolve("member").isStaff()).isTrue();
    }

    @Test
    void anAccountWithNoLibraryResolvesWithoutFailing() {
        member.setLibrary(null);

        ChatContext context = resolver.resolve("member");

        assertThat(context.libraryId()).isNull();
        assertThat(context.libraryName()).isNull();
        assertThat(context.userId()).isEqualTo(21L);
    }

    @Test
    void anUnknownCallerIsRefused() {
        when(userRepository.findByUsername("ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> resolver.resolve("ghost"))
                .isInstanceOf(UserNotFoundException.class);
    }

    @Test
    void theContextCarriesNothingSensitiveAboutTheAccount() {
        member.setPassword("$2a$10$aHashThatMustNotTravel");
        member.setEmail("member@example.invalid");

        assertThat(resolver.resolve("member").toString())
                .as("ids, a library name and a role - what an assistant may be told")
                .doesNotContain("$2a$")
                .doesNotContain("member@example.invalid");
    }
}
