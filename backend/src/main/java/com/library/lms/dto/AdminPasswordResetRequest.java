package com.library.lms.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * The body of {@code POST /api/users/{userId}/password-reset}: the password a
 * member of staff sets for someone who has forgotten theirs.
 *
 * <p><b>The same rule as every other new password.</b> 8 to 72 characters, as
 * account creation and the self-service change require. 72 is BCrypt's limit:
 * past it the encoder ignores the rest, so a longer password would not be what
 * it appeared to be.</p>
 *
 * <p>There is no current password, because the whole point is that its owner
 * has lost it; the caller's authority stands in for it. That is also why an
 * administrator cannot use this for their own account.</p>
 *
 * <p>{@code toString()} leaves the password out, so a request that reaches a
 * log line cannot carry it there.</p>
 */
@Getter
@Setter
@ToString
@NoArgsConstructor
@AllArgsConstructor
public class AdminPasswordResetRequest {

    @ToString.Exclude
    @NotBlank(message = "New password is required")
    @Size(min = 8, max = 72, message = "New password must be between 8 and 72 characters")
    private String newPassword;
}
