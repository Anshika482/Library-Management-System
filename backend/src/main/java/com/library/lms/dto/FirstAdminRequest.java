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
 * The first administrator of a library that is being created.
 *
 * <p><b>No role and no library.</b> The server decides both: the role is always
 * ADMIN, and the library is always the one being created alongside this
 * account. A field for either would be the one way this request could produce
 * an administrator of some other library, or an account that is not an
 * administrator, so it has neither.</p>
 *
 * <p><b>The same rules as every other account.</b> Each constraint below is
 * the one {@link CreateUserRequest} applies to the same field, message
 * included, so an administrator cannot be created under a weaker rule than a
 * member. A test compares the two classes.</p>
 */
@Getter
@Setter
@ToString
@NoArgsConstructor
@AllArgsConstructor
public class FirstAdminRequest {

    @NotBlank(message = "Username is required")
    @Size(min = 3, max = 255, message = "Username must be between 3 and 255 characters")
    private String username;

    @NotBlank(message = "Email is required")
    @Email(message = "Email must be a valid address")
    @Size(max = 255, message = "Email must not exceed 255 characters")
    private String email;

    /**
     * The password the administrator will log in with.
     *
     * <p>Kept out of {@code toString()}, so an accidentally logged request
     * cannot carry it. It is hashed with the application's BCrypt encoder before
     * it is stored, and the plain value is never stored, returned or logged.</p>
     */
    @ToString.Exclude
    @NotBlank(message = "Password is required")
    @Size(min = 8, max = 72, message = "Password must be between 8 and 72 characters")
    private String password;
}
