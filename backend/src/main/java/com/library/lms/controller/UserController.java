package com.library.lms.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.library.lms.dto.CreateUserRequest;
import com.library.lms.dto.UserResponse;
import com.library.lms.dto.UserStatusRequest;
import com.library.lms.dto.UserStatusResponse;
import com.library.lms.service.UserService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;

/**
 * Administrative control over whether an account may be used.
 *
 * <p>One endpoint, and nothing else. There is no listing of users, no way to
 * read another account, and no way to create or delete one: this class exists
 * to turn two switches, and every route it does not have is a route that cannot
 * leak anything.</p>
 *
 * <p>Access is settled before this class is reached. The filter chain requires
 * the ADMIN authority for every method under {@code /api/users}, so a member or
 * a librarian is refused by the chain; the service then confines the change to
 * the administrator's own library.</p>
 */
@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    /**
     * Enables, disables, locks or unlocks one account.
     *
     * <p>PATCH rather than PUT, because the body names only the switches to
     * move: a field left out keeps its current value, which is what makes it
     * possible to lock an account without restating whether it is enabled.</p>
     *
     * <p>The caller's identity comes from {@link Authentication}, never from
     * the request body - the administrator cannot claim to be someone else, and
     * the library the change is confined to is read from their own account.</p>
     *
     * <p>An administrator cannot disable or lock their own account: that
     * answers 400 and changes nothing, because nobody could reverse it through
     * the API.</p>
     *
     * @param userId         the account to change
     * @param request        the switches to move
     * @param authentication the administrator, supplied by the filter chain
     * @return the account's status after the change
     */
    /**
     * Creates an account in the administrator's own library.
     *
     * <p>The caller's identity comes from {@link Authentication} and decides
     * which library the account lands in. Access is settled before this class is
     * reached: the filter chain requires ADMIN for every method under
     * {@code /api/users}, so a member or a librarian never arrives here.</p>
     *
     * <p>Changing your <i>own</i> password is deliberately not here - it lives
     * on the auth endpoint, because everybody needs it and this path is
     * administrators only.</p>
     *
     * @param request        the account to create
     * @param authentication the administrator, supplied by the filter chain
     * @return 201 with the new account, carrying no credential
     */
    @PostMapping
    public ResponseEntity<UserResponse> createUser(@Valid @RequestBody CreateUserRequest request,
            Authentication authentication) {
        UserResponse created = userService.createUser(request, authentication.getName());

        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PatchMapping("/{userId}/status")
    public ResponseEntity<UserStatusResponse> updateStatus(
            @PathVariable @Positive(message = "User id must be a positive number") Long userId,
            @Valid @RequestBody UserStatusRequest request,
            Authentication authentication) {
        UserStatusResponse updated = userService.updateStatus(userId, request, authentication.getName());

        return ResponseEntity.ok(updated);
    }
}
