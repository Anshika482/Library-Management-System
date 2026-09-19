package com.library.lms.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * The body of {@code POST /api/auth/forgot-password}: the address a reset is
 * asked for.
 *
 * <p>Only its shape is validated. Whether an account has that address is never
 * part of the answer - a well-formed address always gets the same 202.</p>
 *
 * <p>{@code toString()} leaves the address out: it is personal data, and which
 * addresses were tried is nothing a log line needs.</p>
 */
@Getter
@Setter
@ToString
@NoArgsConstructor
@AllArgsConstructor
public class ForgotPasswordRequest {

    @ToString.Exclude
    @NotBlank(message = "Email is required")
    @Email(message = "Email must be a valid address")
    @Size(max = 255, message = "Email must not exceed 255 characters")
    private String email;
}
