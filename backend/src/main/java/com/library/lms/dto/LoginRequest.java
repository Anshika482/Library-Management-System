package com.library.lms.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * The credentials a client sends to log in.
 *
 * <p>Two fields and nothing else. There is no role, no id and no expiry: a
 * caller says who they claim to be and proves it, and every other fact about
 * the account is looked up on the server. Accepting anything more here would
 * let the client assert something it has not proved.</p>
 *
 * <p>Input only. This object never travels back out in a response, and the
 * password it carries is <b>plaintext</b> for the brief moment between arriving
 * and being checked against the stored BCrypt hash. Nothing in this class
 * stores, hashes or transforms it; that comparison belongs to Spring Security's
 * authentication manager and the encoder configured alongside it.</p>
 */
@Getter
@Setter
@ToString
@NoArgsConstructor
@AllArgsConstructor
public class LoginRequest {

    /**
     * The account's login name.
     *
     * <p>{@code @NotBlank} rather than {@code @NotNull}: a name of spaces is as
     * unusable as a missing one, and rejecting it here keeps a pointless
     * database lookup from happening at all.</p>
     */
    @NotBlank(message = "Username is required")
    private String username;

    /**
     * The plaintext password being offered for checking.
     *
     * <p>{@code @ToString.Exclude} is the important annotation on this field,
     * and it is here for the same reason the User entity excludes its hash -
     * only more so. That field holds a BCrypt hash; this one holds the password
     * itself. Lombok would otherwise write it into {@code toString()}, and any
     * framework or debug line that prints a request object would copy a live
     * credential into a log file, where it would be retained, shipped and
     * searched long after the request was served.</p>
     */
    @ToString.Exclude
    @NotBlank(message = "Password is required")
    private String password;
}
