package com.library.lms.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * What someone supplies to change their own password.
 *
 * <p><b>It names no account.</b> Whose password changes is decided by who is
 * authenticated, never by the body - a username field here would turn a
 * self-service endpoint into a way to overwrite somebody else's credentials.
 * For the same reason there is no role and no library: this request can change
 * exactly one thing.</p>
 *
 * <p>Both fields are kept out of {@code toString()}. A request object reaches a
 * log far more easily than a password should.</p>
 */
@Getter
@Setter
@ToString
@NoArgsConstructor
@AllArgsConstructor
public class ChangePasswordRequest {

    /**
     * The password the account has now.
     *
     * <p>Required so that a stolen token alone is not enough to take an account
     * over permanently: whoever is changing the password has to know the
     * current one.</p>
     */
    @ToString.Exclude
    @NotBlank(message = "Current password is required")
    @Size(max = 72, message = "Current password must not exceed 72 characters")
    private String currentPassword;

    /**
     * The password the account will have.
     *
     * <p>The same 8-to-72 range the create endpoint applies, so an account
     * cannot be created under one rule and then moved outside it.</p>
     */
    @ToString.Exclude
    @NotBlank(message = "New password is required")
    @Size(min = 8, max = 72, message = "New password must be between 8 and 72 characters")
    private String newPassword;
}
