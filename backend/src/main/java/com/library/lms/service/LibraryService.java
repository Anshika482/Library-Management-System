package com.library.lms.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.library.lms.dto.CreateLibraryRequest;
import com.library.lms.dto.LibraryResponse;
import com.library.lms.entity.Library;
import com.library.lms.entity.User;
import com.library.lms.exception.DuplicateLibraryException;
import com.library.lms.exception.UserNotFoundException;
import com.library.lms.repository.LibraryRepository;
import com.library.lms.repository.UserRepository;

/**
 * Registers new libraries.
 *
 * <p>That the caller is an administrator is decided by the filter chain, which
 * requires the ADMIN authority for every method on {@code /api/libraries/**}.
 * This service is reached only after that.</p>
 *
 * <p><b>It creates a library and changes nothing else.</b> The creator is not
 * moved into the new library, given a role in it, or recorded as its owner;
 * their account is read only to say in the log who made the change. No account
 * is created in the new library either, so it starts empty.</p>
 */
@Service
public class LibraryService {

    private static final Logger log = LoggerFactory.getLogger(LibraryService.class);

    private final LibraryRepository libraryRepository;

    private final UserRepository userRepository;

    public LibraryService(LibraryRepository libraryRepository, UserRepository userRepository) {
        this.libraryRepository = libraryRepository;
        this.userRepository = userRepository;
    }

    /**
     * Creates a library under a name no other library has.
     *
     * <p>The name is trimmed first, so surrounding spaces cannot make a second
     * "Central Library", and the check ignores case for the same reason. The
     * check is what turns an ordinary clash into a clear 400; the unique index
     * on the column is the actual guarantee, and it is what stops two requests
     * that pass the check at the same moment - the second as a 409 from the
     * integrity handler.</p>
     *
     * @param request               the library to create
     * @param authenticatedUsername the administrator creating it, for the log
     * @return the new library
     * @throws DuplicateLibraryException if a library already has that name
     */
    @Transactional
    public LibraryResponse createLibrary(CreateLibraryRequest request, String authenticatedUsername) {
        User creator = userRepository.findByUsername(authenticatedUsername)
                .orElseThrow(() -> new UserNotFoundException(authenticatedUsername));

        String name = request.getName().trim();

        if (libraryRepository.existsByNameIgnoreCase(name)) {
            throw new DuplicateLibraryException();
        }

        Library library = new Library();
        library.setName(name);

        Library saved = libraryRepository.save(library);

        // Who created which library, identified by ids. A new tenant appearing
        // is exactly the kind of event that has to be reconstructable, and the
        // name is left out because it is whatever the caller typed.
        log.info("Library created by admin='{}' of library id={}: new library id={}",
                creator.getUsername(), creator.getLibrary().getId(), saved.getId());

        return new LibraryResponse(saved.getId(), saved.getName(), saved.getCreatedAt());
    }
}
