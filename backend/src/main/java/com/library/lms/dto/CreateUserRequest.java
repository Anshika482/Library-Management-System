package com.library.lms.dto;

import com.library.lms.entity.Role;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * What an administrator supplies to create an account.
 *
 * <p><b>There is no library here, deliberately.</b> The new account always
 * belongs to the library of the administrator creating it, read from their own
 * account. A field for it would be the one thing on this request capable of
 * reaching into another tenant, so the request cannot express the idea at all -
 * an unknown property in the body is ignored rather than honoured.</p>
 *
 * <p><b>There is no account status either.</b> A new account is enabled and
 * unlocked; disabling one is a separate, deliberate call to the status
 * endpoint.</p>
 */
@Getter
@Setter
@ToString
@NoArgsConstructor
@AllArgsConstructor
public class CreateUserRequest {

    /**
     * The login name.
     *
     * <p>The maximum matches the {@code users.username} column exactly. The
     * minimum is a floor rather than a policy: a one-character login is almost
     * certainly a mistake.</p>
     */
    @NotBlank(message = "Username is required")
    @Size(min = 3, max = 255, message = "Username must be between 3 and 255 characters")
    private String username;

    /**
     * The account's email address.
     *
     * <p>Required because the column is, not because this endpoint wants it:
     * {@code users.email} is NOT NULL and UNIQUE, so an account cannot be
     * written without one.</p>
     */
    @NotBlank(message = "Email is required")
    @Email(message = "Email must be a valid address")
    @Size(max = 255, message = "Email must not exceed 255 characters")
    private String email;

    /**
     * The password the account will log in with.
     *
     * <p>Kept out of {@code toString()}, so an accidentally logged request
     * cannot carry it. It is hashed the moment it reaches the service and the
     * plain value is never stored, returned or written down.</p>
     *
     * <p>72 is BCrypt's own limit, matching the login endpoint: beyond it the
     * algorithm reads nothing further, so allowing more would silently ignore
     * the rest of what someone typed.</p>
     */
    @ToString.Exclude
    @NotBlank(message = "Password is required")
    @Size(min = 8, max = 72, message = "Password must be between 8 and 72 characters")
    private String password;

    /**
     * What the account may do.
     *
     * <p>Which values are actually permitted is the service's decision, not a
     * validation annotation's: creating an administrator is refused there, so
     * the rule lives next to the library check it belongs with rather than
     * being split across two layers.</p>
     */
    @NotNull(message = "Role is required")
    private Role role;
}
