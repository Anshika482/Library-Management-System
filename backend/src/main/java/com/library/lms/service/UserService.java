package com.library.lms.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.library.lms.dto.UserStatusRequest;
import com.library.lms.dto.UserStatusResponse;
import com.library.lms.entity.User;
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

    private final UserRepository userRepository;

    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
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
