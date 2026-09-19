package com.library.lms.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * The body of {@code POST /api/auth/reset-password}: a reset token and the
 * password it is to set.
 *
 * <p>The new password follows the one rule every password in this system
 * follows - 8 to 72 characters, 72 being BCrypt's limit. Validation runs before
 * the token is looked at, so a password that breaks the rule is refused without
 * using the token up.</p>
 *
 * <p>{@code toString()} leaves both fields out: each is a credential.</p>
 */
@Getter
@Setter
@ToString
@NoArgsConstructor
@AllArgsConstructor
public class ResetPasswordRequest {

    /** Issued tokens are 43 characters; the ceiling only stops an absurd value reaching the hash. */
    @ToString.Exclude
    @NotBlank(message = "Token is required")
    @Size(max = 128, message = "Token must not exceed 128 characters")
    private String token;

    @ToString.Exclude
    @NotBlank(message = "New password is required")
    @Size(min = 8, max = 72, message = "New password must be between 8 and 72 characters")
    private String newPassword;
}
