package com.library.lms.service;

import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.library.lms.dto.ChangePasswordRequest;
import com.library.lms.dto.AdminPasswordResetRequest;
import com.library.lms.dto.CreateUserRequest;
import com.library.lms.dto.PagedResponse;
import com.library.lms.dto.UserResponse;
import com.library.lms.dto.UserStatusRequest;
import com.library.lms.dto.UserStatusResponse;
import com.library.lms.entity.AuditAction;
import com.library.lms.entity.Library;
import com.library.lms.entity.Role;
import com.library.lms.entity.User;
import com.library.lms.exception.DuplicateAccountException;
import com.library.lms.exception.InvalidCurrentPasswordException;
import com.library.lms.exception.InvalidPaginationException;
import com.library.lms.exception.InvalidSortException;
import com.library.lms.exception.PasswordResetNotAllowedException;
import com.library.lms.exception.RoleNotAssignableException;
import com.library.lms.exception.SelfLockoutException;
import com.library.lms.exception.SelfPasswordResetException;
import com.library.lms.exception.UserDirectoryAccessDeniedException;
import com.library.lms.exception.UserNotFoundException;
import com.library.lms.repository.UserRepository;
import com.library.lms.repository.UserSpecifications;

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

    private final RefreshTokenService refreshTokenService;

    private final LoginAttemptService loginAttemptService;

    private final AuditService auditService;

    public UserService(UserRepository userRepository, PasswordEncoder passwordEncoder,
            RefreshTokenService refreshTokenService, LoginAttemptService loginAttemptService,
            AuditService auditService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.refreshTokenService = refreshTokenService;
        this.loginAttemptService = loginAttemptService;
        this.auditService = auditService;
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
            auditService.recordFailure(AuditAction.USER_CREATED, library.getId(), administrator.getId(),
                    AuditTarget.none());
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
        auditService.recordSuccess(AuditAction.USER_CREATED, library.getId(), administrator.getId(),
                AuditTarget.user(saved.getId()));

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
     * <p><b>Every refresh session ends.</b> The account's refresh tokens are
     * revoked in the same transaction, so a refresh token taken before the
     * change - from a lost device, say - cannot keep a session going for days
     * after it. An access token already issued lasts until it expires, as
     * before.</p>
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
            auditService.recordFailure(AuditAction.PASSWORD_CHANGED, user.getLibrary().getId(), user.getId(),
                    AuditTarget.user(user.getId()));
            throw new InvalidCurrentPasswordException();
        }

        user.setPassword(passwordEncoder.encode(request.getNewPassword()));
        userRepository.save(user);

        int revoked = refreshTokenService.revokeAllFor(user);
        auditService.recordSuccess(AuditAction.PASSWORD_CHANGED, user.getLibrary().getId(), user.getId(),
                AuditTarget.user(user.getId()));

        log.info("Password changed for username='{}': {} live refresh token(s) revoked",
                user.getUsername(), revoked);
    }

    // ---------- staff password reset ----------

    /**
     * Sets a new password for someone who has forgotten theirs.
     *
     * <p><b>Who may reset whose.</b> An administrator may reset any account in
     * their library except their own; a librarian may reset members only; a
     * member may reset nobody's - the filter chain stops them first, and this
     * refuses them again. The checks run in an order that gives nothing away
     * across libraries: the account is looked up inside the caller's library
     * before anything else is decided, so another library's account is the same
     * 404 as an id that does not exist.</p>
     *
     * <p><b>Not for your own account.</b> This asks for no current password -
     * the caller's authority stands in for it - so allowing it on the caller's
     * own account would let a stolen administrator token set a password and keep
     * the account. {@code POST /api/auth/password} is the way to change your
     * own, and it is unchanged.</p>
     *
     * <p><b>What changes with it.</b> The password is stored through the same
     * BCrypt encoder as every other. Every refresh session of the account is
     * revoked in the same transaction, so whoever was using it - perhaps the
     * reason for the reset - has to sign in again, with the new password. The
     * account's failed-login counter is cleared, so an owner who was blocked for
     * guessing can sign in at once. Access tokens already issued run out on
     * their own, within {@code security.access-token.validity}.</p>
     *
     * <p>The log records who reset which account, by id. Never the password and
     * never the hash.</p>
     *
     * @throws PasswordResetNotAllowedException if the caller is a member, or a
     *                                          librarian naming a staff account
     * @throws UserNotFoundException            if the account is not in the
     *                                          caller's library
     * @throws SelfPasswordResetException       if the account is the caller's own
     */
    @Transactional
    public void resetPassword(Long userId, AdminPasswordResetRequest request, String authenticatedUsername) {
        User caller = authenticatedUser(authenticatedUsername);

        if (caller.getRole() != Role.ROLE_ADMIN && caller.getRole() != Role.ROLE_LIBRARIAN) {
            auditService.recordFailure(AuditAction.PASSWORD_RESET_BY_STAFF, caller.getLibrary().getId(),
                    caller.getId(), AuditTarget.none());
            throw new PasswordResetNotAllowedException();
        }

        User target = userRepository.findByIdAndLibraryId(userId, caller.getLibrary().getId())
                .orElseThrow(() -> new UserNotFoundException(userId));

        if (caller.getRole() == Role.ROLE_LIBRARIAN && target.getRole() != Role.ROLE_MEMBER) {
            log.warn("Password reset refused for librarian='{}': user id={} is not a member",
                    caller.getUsername(), target.getId());
            auditService.recordFailure(AuditAction.PASSWORD_RESET_BY_STAFF, caller.getLibrary().getId(),
                    caller.getId(), AuditTarget.user(target.getId()));
            throw new PasswordResetNotAllowedException();
        }

        if (Objects.equals(target.getId(), caller.getId())) {
            log.warn("Password reset refused for admin='{}': an account cannot reset its own password here",
                    caller.getUsername());
            auditService.recordFailure(AuditAction.PASSWORD_RESET_BY_STAFF, caller.getLibrary().getId(),
                    caller.getId(), AuditTarget.user(target.getId()));
            throw new SelfPasswordResetException();
        }

        target.setPassword(passwordEncoder.encode(request.getNewPassword()));
        userRepository.save(target);

        int revoked = refreshTokenService.revokeAllFor(target);
        loginAttemptService.reset(target.getUsername());
        auditService.recordSuccess(AuditAction.PASSWORD_RESET_BY_STAFF, caller.getLibrary().getId(), caller.getId(),
                AuditTarget.user(target.getId()));

        log.info("Password reset by {}='{}' for user id={}: {} live refresh token(s) revoked, login block cleared",
                caller.getRole() == Role.ROLE_ADMIN ? "admin" : "librarian", caller.getUsername(), target.getId(),
                revoked);
    }

    // ---------- the user directory ----------

    /** The most accounts one page may hold - the same ceiling as every other list in the API. */
    private static final int MAX_PAGE_SIZE = 50;

    /**
     * The sort names the directory accepts, each mapped to the entity property
     * it sorts by. The value handed to {@code Sort.by} is always the right-hand
     * side, so a caller can never name a property this map does not list.
     */
    private static final Map<String, String> SORTABLE_FIELDS = Map.of(
            "id", "id",
            "username", "username",
            "email", "email",
            "role", "role");

    /**
     * One page of the caller's library's accounts.
     *
     * <p><b>Scoped to the caller's library, always.</b> The library comes from
     * the caller's own account, never from the request, and it is the first
     * predicate of every query - search and filters narrow within it and
     * cannot reach past it.</p>
     *
     * <p><b>What each role sees.</b> An administrator sees every account and
     * may filter by any role. A librarian sees members only: the role filter is
     * forced to members, and asking for administrators or librarians is refused
     * with {@link UserDirectoryAccessDeniedException} rather than quietly
     * answered with members. A member is refused outright; the filter chain
     * already stops them, and this is the second lock.</p>
     *
     * <p>Every filter given must match. The response carries the same fields as
     * every other account response - never the password hash.</p>
     *
     * @throws UserDirectoryAccessDeniedException if the caller may not see the
     *                                            requested role, or is a member
     * @throws InvalidPaginationException         if page or size is out of range
     * @throws InvalidSortException               if the field or direction is
     *                                            unsupported
     */
    @Transactional(readOnly = true)
    public PagedResponse<UserResponse> listUsers(int page, int size, String sortBy, String direction, String keyword,
            Role role, Boolean enabled, Boolean accountNonLocked, String authenticatedUsername) {
        User caller = authenticatedUser(authenticatedUsername);
        Role visibleRole = visibleRole(caller, role);

        validatePagination(page, size);
        Pageable pageable = PageRequest.of(page, size, resolveSort(sortBy, direction));

        Specification<User> filter = UserSpecifications.belongsToLibrary(caller.getLibrary().getId());

        if (visibleRole != null) {
            filter = filter.and(UserSpecifications.hasRole(visibleRole));
        }
        if (enabled != null) {
            filter = filter.and(UserSpecifications.isEnabled(enabled));
        }
        if (accountNonLocked != null) {
            filter = filter.and(UserSpecifications.isAccountNonLocked(accountNonLocked));
        }
        if (keyword != null && !keyword.isBlank()) {
            filter = filter.and(UserSpecifications.matchesKeyword(keyword.trim()));
        }

        Page<User> users = userRepository.findAll(filter, pageable);

        return new PagedResponse<>(
                users.getContent().stream().map(UserService::toResponse).toList(),
                users.getNumber(),
                users.getSize(),
                users.getTotalElements(),
                users.getTotalPages());
    }

    /**
     * One account of the caller's library.
     *
     * <p><b>404 for anything the caller may not see</b> - an account in another
     * library, or, for a librarian, one that is not a member. Both are answered
     * exactly as an id that does not exist, so the endpoint cannot be used to
     * learn which ids belong to staff or to other libraries.</p>
     *
     * @throws UserDirectoryAccessDeniedException if the caller is a member
     * @throws UserNotFoundException              if the account is not there to
     *                                            be seen
     */
    @Transactional(readOnly = true)
    public UserResponse getUser(Long userId, String authenticatedUsername) {
        User caller = authenticatedUser(authenticatedUsername);
        requireStaff(caller);

        User target = userRepository.findByIdAndLibraryId(userId, caller.getLibrary().getId())
                .orElseThrow(() -> new UserNotFoundException(userId));

        if (caller.getRole() == Role.ROLE_LIBRARIAN && target.getRole() != Role.ROLE_MEMBER) {
            throw new UserNotFoundException(userId);
        }

        return toResponse(target);
    }

    /**
     * The role the caller's listing is limited to, or null for no limit.
     *
     * @throws UserDirectoryAccessDeniedException if the caller may not see the
     *                                            requested role, or is a member
     */
    private static Role visibleRole(User caller, Role requested) {
        requireStaff(caller);

        if (caller.getRole() == Role.ROLE_ADMIN) {
            return requested;
        }

        if (requested != null && requested != Role.ROLE_MEMBER) {
            throw new UserDirectoryAccessDeniedException();
        }

        return Role.ROLE_MEMBER;
    }

    /** Members have no directory access; the filter chain refuses them first, and this refuses them again. */
    private static void requireStaff(User caller) {
        if (caller.getRole() != Role.ROLE_ADMIN && caller.getRole() != Role.ROLE_LIBRARIAN) {
            throw new UserDirectoryAccessDeniedException();
        }
    }

    /** The same rules, and the same messages, as every other paged list in the API. */
    private static void validatePagination(int page, int size) {
        if (page < 0) {
            throw new InvalidPaginationException("Page must be 0 or greater, but was " + page);
        }
        if (size < 1) {
            throw new InvalidPaginationException("Size must be at least 1, but was " + size);
        }
        if (size > MAX_PAGE_SIZE) {
            throw new InvalidPaginationException(
                    "Size must not exceed " + MAX_PAGE_SIZE + ", but was " + size);
        }
    }

    /**
     * Turns the requested field and direction into a safe {@link Sort}, with id
     * as the tie-breaker so page boundaries are deterministic - the same rules
     * as the book list.
     */
    private static Sort resolveSort(String sortBy, String direction) {
        String property = SORTABLE_FIELDS.get(sortBy);
        if (property == null) {
            throw new InvalidSortException("Unsupported sort field. Allowed fields are: "
                    + String.join(", ", new TreeSet<>(SORTABLE_FIELDS.keySet())));
        }

        Sort.Direction sortDirection;
        if ("asc".equalsIgnoreCase(direction)) {
            sortDirection = Sort.Direction.ASC;
        } else if ("desc".equalsIgnoreCase(direction)) {
            sortDirection = Sort.Direction.DESC;
        } else {
            throw new InvalidSortException("Unsupported sort direction. Allowed directions are: asc, desc");
        }

        Sort sort = Sort.by(sortDirection, property);

        return "id".equals(property) ? sort : sort.and(Sort.by(Sort.Direction.ASC, "id"));
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
     * <p><b>No administrator can shut themselves out.</b> This endpoint is the
     * only way back from a disabled or locked account, and it needs an
     * administrator who can still log in; in a library with one administrator,
     * disabling your own account would leave nobody able to undo it. So a
     * request that would disable or lock the caller's own account is refused,
     * before either switch is set - a refused request changes nothing, including
     * a switch it was allowed to move. Enabling or unlocking your own account, or
     * sending neither, stays allowed: none of those can lock anyone out. Other
     * accounts in the library, other administrators included, are unaffected.</p>
     *
     * @param userId                the account to change
     * @param request               which switches to move
     * @param authenticatedUsername the administrator making the change
     * @return the account's identity and its status after the change
     * @throws UserNotFoundException if the id is unknown, or belongs to another
     *                               library
     * @throws SelfLockoutException  if the administrator would disable or lock
     *                               their own account
     */
    @Transactional
    public UserStatusResponse updateStatus(Long userId, UserStatusRequest request,
            String authenticatedUsername) {
        User administrator = authenticatedUser(authenticatedUsername);
        Long libraryId = administrator.getLibrary().getId();

        User target = userRepository.findByIdAndLibraryId(userId, libraryId)
                .orElseThrow(() -> new UserNotFoundException(userId));

        // After the scoped lookup, so an id from another library is still the
        // same 404 as before, and before anything is set, so a refusal leaves
        // the account exactly as it was.
        if (Objects.equals(target.getId(), administrator.getId()) && wouldLockOut(request)) {
            log.warn("Self-lockout refused for admin='{}': user id={}", administrator.getUsername(), target.getId());
            auditService.recordFailure(AuditAction.USER_STATUS_CHANGED, libraryId, administrator.getId(),
                    AuditTarget.user(target.getId()));
            throw new SelfLockoutException();
        }

        if (request.getEnabled() != null) {
            target.setEnabled(request.getEnabled());
        }
        if (request.getAccountNonLocked() != null) {
            target.setAccountNonLocked(request.getAccountNonLocked());
        }

        User saved = userRepository.save(target);
        auditService.recordSuccess(AuditAction.USER_STATUS_CHANGED, libraryId, administrator.getId(),
                AuditTarget.user(saved.getId()));

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

    /** Whether the request would disable or lock the account it is applied to. */
    private static boolean wouldLockOut(UserStatusRequest request) {
        return Boolean.FALSE.equals(request.getEnabled()) || Boolean.FALSE.equals(request.getAccountNonLocked());
    }

    private User authenticatedUser(String authenticatedUsername) {
        return userRepository.findByUsername(authenticatedUsername)
                .orElseThrow(() -> new UserNotFoundException(authenticatedUsername));
    }
}
