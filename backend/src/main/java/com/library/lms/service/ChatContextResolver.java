package com.library.lms.service;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.library.lms.entity.Library;
import com.library.lms.entity.User;
import com.library.lms.exception.UserNotFoundException;
import com.library.lms.repository.UserRepository;

/**
 * Reads the caller's account and turns it into a {@link ChatContext}.
 *
 * <p><b>A class of its own so the database transaction ends here.</b> This is
 * the only part of answering a question that touches the database, and it is
 * deliberately separated from the part that calls a provider over the network:
 * a transaction held open across that call would pin a connection for as long
 * as the provider took to answer, and a slow provider would drain the pool for
 * every other request in the application. Spring's transaction proxy only
 * applies between beans, so the split has to be a real boundary rather than a
 * second method on {@link ChatService}.</p>
 *
 * <p>Read-only: resolving a caller changes nothing.</p>
 */
@Component
public class ChatContextResolver {

    private final UserRepository userRepository;

    public ChatContextResolver(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /**
     * The caller's own library, id and role.
     *
     * @param authenticatedUsername the caller, from the security context
     * @throws UserNotFoundException if the authenticated name matches no account
     */
    @Transactional(readOnly = true)
    public ChatContext resolve(String authenticatedUsername) {
        User caller = userRepository.findByUsername(authenticatedUsername)
                .orElseThrow(() -> new UserNotFoundException(authenticatedUsername));

        Library library = caller.getLibrary();

        return new ChatContext(
                library == null ? null : library.getId(),
                library == null ? null : library.getName(),
                caller.getId(),
                caller.getRole());
    }
}
