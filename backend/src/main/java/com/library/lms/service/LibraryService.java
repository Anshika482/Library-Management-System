package com.library.lms.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.library.lms.dto.CreateLibraryRequest;
import com.library.lms.dto.FirstAdminRequest;
import com.library.lms.dto.LibraryResponse;
import com.library.lms.dto.UserResponse;
import com.library.lms.entity.Library;
import com.library.lms.entity.Role;
import com.library.lms.entity.User;
import com.library.lms.exception.DuplicateAccountException;
import com.library.lms.exception.DuplicateLibraryException;
import com.library.lms.exception.UserNotFoundException;
import com.library.lms.repository.LibraryRepository;
import com.library.lms.repository.UserRepository;

/**
 * Registers new libraries, each with its first administrator.
 *
 * <p>That the caller is an administrator is decided by the filter chain, which
 * requires the ADMIN authority for every method on {@code /api/libraries/**}.
 * This service is reached only after that.</p>
 *
 * <p><b>A library and its first administrator arrive together or not at
 * all.</b> A library with no account in it is unusable - nobody can log in to
 * give it staff - so both are written in one transaction, and if the account
 * cannot be created the library is rolled back with it.</p>
 *
 * <p><b>The creator is not part of it.</b> Their account is read only to say in
 * the log who made the change. It is not moved into the new library, given a
 * role there, or recorded as its owner.</p>
 */
@Service
public class LibraryService {

    private static final Logger log = LoggerFactory.getLogger(LibraryService.class);

    private final LibraryRepository libraryRepository;

    private final UserRepository userRepository;

    private final PasswordEncoder passwordEncoder;

    public LibraryService(LibraryRepository libraryRepository, UserRepository userRepository,
            PasswordEncoder passwordEncoder) {
        this.libraryRepository = libraryRepository;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * Creates a library and its first administrator.
     *
     * <p><b>The name</b> is trimmed first, so surrounding spaces cannot make a
     * second "Central Library", and the check ignores case for the same reason.
     * The check turns an ordinary clash into a clear 400; the unique index on
     * the column is the actual guarantee.</p>
     *
     * <p><b>The administrator's role and library are not read from the
     * request</b>, which has no fields for either. The role is always ADMIN and
     * the library is always the one inserted just before, so this cannot produce
     * an administrator of an existing library, or an account in the new library
     * that is anything but its administrator.</p>
     *
     * <p><b>{@code @Transactional} is what makes the pair atomic.</b> The
     * library row is written before the account is checked, so a taken username
     * or email is refused after that row exists, and the exception rolls it back.
     * The same holds for a failure the checks cannot see, such as two requests
     * racing for one username: the unique index refuses the second, as a 409
     * from the integrity handler, and its library goes too.</p>
     *
     * @param request               the library and its first administrator
     * @param authenticatedUsername the administrator creating it, for the log
     * @return the new library and its administrator
     * @throws DuplicateLibraryException if a library already has that name
     * @throws DuplicateAccountException if the administrator's username or
     *                                   email is already taken
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

        Library savedLibrary = libraryRepository.save(library);

        User firstAdmin = createFirstAdmin(request.getAdmin(), savedLibrary);

        // Who created which library and which administrator, by id. A new tenant
        // and a new administrator appearing are exactly the events that have to
        // be reconstructable. Names are left out because they are whatever the
        // caller typed, and no password or hash is ever written here.
        log.info("Library created by admin='{}' of library id={}: new library id={} with first admin id={}",
                creator.getUsername(), creator.getLibrary().getId(), savedLibrary.getId(), firstAdmin.getId());

        return new LibraryResponse(
                savedLibrary.getId(),
                savedLibrary.getName(),
                savedLibrary.getCreatedAt(),
                toResponse(firstAdmin));
    }

    /**
     * Creates the new library's administrator by the rules the user endpoint
     * applies to every other account.
     *
     * <p>Username and email are trimmed and checked against every account in
     * every library, because both columns are unique across the whole table. A
     * clash gets the user endpoint's single message, which does not say which
     * of the two was taken. The password goes through the application's one
     * BCrypt encoder; the account starts enabled and unlocked because the entity
     * says so.</p>
     */
    private User createFirstAdmin(FirstAdminRequest request, Library library) {
        String username = request.getUsername().trim();
        String email = request.getEmail().trim();

        if (userRepository.existsByUsername(username) || userRepository.existsByEmail(email)) {
            throw new DuplicateAccountException();
        }

        User admin = new User();
        admin.setUsername(username);
        admin.setEmail(email);
        admin.setPassword(passwordEncoder.encode(request.getPassword()));
        admin.setRole(Role.ROLE_ADMIN);
        admin.setLibrary(library);

        return userRepository.save(admin);
    }

    private static UserResponse toResponse(User user) {
        return new UserResponse(
                user.getId(),
                user.getUsername(),
                user.getEmail(),
                user.getRole(),
                user.isEnabled(),
                user.isAccountNonLocked());
    }
}
