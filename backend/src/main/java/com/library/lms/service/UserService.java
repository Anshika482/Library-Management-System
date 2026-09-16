package com.library.lms.service;

import java.util.EnumSet;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.library.lms.dto.ChangePasswordRequest;
import com.library.lms.dto.CreateUserRequest;
import com.library.lms.dto.UserResponse;
import com.library.lms.dto.UserStatusRequest;
import com.library.lms.dto.UserStatusResponse;
import com.library.lms.entity.Library;
import com.library.lms.entity.Role;
import com.library.lms.entity.User;
import com.library.lms.exception.DuplicateAccountException;
import com.library.lms.exception.InvalidCurrentPasswordException;
import com.library.lms.exception.RoleNotAssignableException;
import com.library.lms.exception.UserNotFoundException;
import com.library.lms.repository.UserRepository;

/**
 * Turns an administrator's request to enable, disable, lock or unlock an
 * account into a change on one row.
 *
 * <p><b>Inside one library only.</b> The account being changed is looked up by
 * id <i>and</i> by the administrator's own library, so an id belonging to
 * another library simply is not found. That is the same answer an id belonging
 * to nobody gets, which is the point: an administrator cannot use this endpoint
 * to discover that another library's user ids exist.</p>
 *
 * <p><b>Only the two switches.</b> The role is never read from the request and
 * never written, and neither is the library. Whether an account works is a
 * different question from what it may do, and this service answers only the
 * first.</p>
 *
 * <p>That the caller is an administrator is decided by the filter chain, which
 * requires the ADMIN authority for every method on {@code /api/users/**}. This
 * service is reached only after that, and adds the tenant rule the chain cannot
 * know about.</p>
 */
@Service
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    /** The roles an administrator may hand out. ADMIN is deliberately absent. */
    private static final Set<Role> ASSIGNABLE_ROLES = EnumSet.of(Role.ROLE_MEMBER, Role.ROLE_LIBRARIAN);

    private final UserRepository userRepository;

    private final PasswordEncoder passwordEncoder;

    public UserService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * Creates an account in the administrator's own library.
     *
     * <p><b>The library is never taken from the request.</b> It is read from the
     * administrator's own account, so there is no input that could place a new
     * account in another tenant - the request cannot even express the idea.</p>
     *
     * <p><b>An administrator cannot create an administrator.</b> Control over who
     * may use the system is the one privilege that would otherwise be
     * self-propagating, letting a single compromised admin account mint more of
     * itself. Only members and librarians can be created here.</p>
     *
     * <p>The password is hashed before the row is built, so the plain value
     * exists only for the length of this call and is never stored, logged or
     * returned. New accounts are enabled and unlocked because the entity says
     * so; disabling one is a separate, deliberate call.</p>
     *
     * <p>The duplicate check is a courtesy, not the guarantee: two simultaneous
     * creations could both pass it, and the unique index is what actually stops
     * the second - as a 409 from the integrity handler rather than this 400.</p>
     *
     * @param request               the account to create
     * @param authenticatedUsername the administrator creating it
     * @return the new account, without anything resembling a credential
     * @throws RoleNotAssignableException if the role asked for is not one an
     *                                    administrator may grant
     * @throws DuplicateAccountException  if the username or email is taken
     */
    @Transactional
    public UserResponse createUser(CreateUserRequest request, String authenticatedUsername) {
        User administrator = authenticatedUser(authenticatedUsername);
        Library library = administrator.getLibrary();

        if (!ASSIGNABLE_ROLES.contains(request.getRole())) {
            throw new RoleNotAssignableException();
        }

        String username = request.getUsername().trim();
        String email = request.getEmail().trim();

        if (userRepository.existsByUsername(username) || userRepository.existsByEmail(email)) {
            throw new DuplicateAccountException();
        }

        User user = new User();
        user.setUsername(username);
        user.setEmail(email);
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        user.setRole(request.getRole());
        user.setLibrary(library);

        User saved = userRepository.save(user);

        // Who created whom, and with what role. An account appearing is exactly
        // the kind of event that has to be reconstructable afterwards. The
        // password is not here, and neither is its hash.
        log.info("Account created by admin='{}': user id={} username='{}' role={} library id={}",
                administrator.getUsername(), saved.getId(), saved.getUsername(),
                saved.getRole(), library.getId());

        return toResponse(saved);
    }

    /**
     * Changes the password of the account making the request.
     *
     * <p><b>It can only ever change the caller's own.</b> The account is
     * resolved from the authenticated name; the request body names nobody, so
     * there is no id or username here to point at somebody else.</p>
     *
     * <p><b>The current password is required</b> even though the caller is
     * already authenticated. A token alone should not be enough to take an
     * account over permanently - a stolen one expires, a changed password does
     * not.</p>
     *
     * <p>Only the password is touched. The role and the library are not read
     * from the request and not written, so this endpoint cannot be used to
     * promote an account or move it between libraries.</p>
     *
     * @param request               the current and replacement passwords
     * @param authenticatedUsername whose password is being changed
     * @throws InvalidCurrentPasswordException if the current password is wrong
     */
    @Transactional
    public void changePassword(ChangePasswordRequest request, String authenticatedUsername) {
        User user = authenticatedUser(authenticatedUsername);

        if (!passwordEncoder.matches(request.getCurrentPassword(), user.getPassword())) {
            // Worth recording: a run of these is somebody working with a stolen
            // token and guessing. Neither password is written, of course.
            log.warn("Password change refused for username='{}': the current password did not match",
                    user.getUsername());
            throw new InvalidCurrentPasswordException();
        }

        user.setPassword(passwordEncoder.encode(request.getNewPassword()));
        userRepository.save(user);

        log.info("Password changed for username='{}'", user.getUsername());
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

    /**
     * Applies whichever switches the request mentions.
     *
     * <p>{@code @Transactional} so the read and the write are one unit: without
     * it the row could change between being loaded and being saved, and this
     * method would write back a status built from a stale read.</p>
     *
     * <p>A field left null is left alone. A request that mentions neither
     * changes nothing and returns the account as it stands, which is a harmless
     * answer to a request that asked for nothing.</p>
     *
     * @param userId                the account to change
     * @param request               which switches to move
     * @param authenticatedUsername the administrator making the change
     * @return the account's identity and its status after the change
     * @throws UserNotFoundException if the id is unknown, or belongs to another
     *                               library
     */
    @Transactional
    public UserStatusResponse updateStatus(Long userId, UserStatusRequest request,
            String authenticatedUsername) {
        User administrator = authenticatedUser(authenticatedUsername);
        Long libraryId = administrator.getLibrary().getId();

        User target = userRepository.findByIdAndLibraryId(userId, libraryId)
                .orElseThrow(() -> new UserNotFoundException(userId));

        if (request.getEnabled() != null) {
            target.setEnabled(request.getEnabled());
        }
        if (request.getAccountNonLocked() != null) {
            target.setAccountNonLocked(request.getAccountNonLocked());
        }

        User saved = userRepository.save(target);

        // Who changed whose account, and to what. An administrative change to
        // who may use the system is exactly the kind of event that has to be
        // reconstructable afterwards. No password, no hash, no token.
        log.info("Account status set by admin='{}': user id={} enabled={} accountNonLocked={}",
                administrator.getUsername(), saved.getId(), saved.isEnabled(), saved.isAccountNonLocked());

        return new UserStatusResponse(
                saved.getId(),
                saved.getUsername(),
                saved.isEnabled(),
                saved.isAccountNonLocked());
    }

    private User authenticatedUser(String authenticatedUsername) {
        return userRepository.findByUsername(authenticatedUsername)
                .orElseThrow(() -> new UserNotFoundException(authenticatedUsername));
    }
}
