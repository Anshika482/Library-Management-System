package com.library.lms.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * The refresh token a client sends to refresh a session or to log out.
 *
 * <p>In the body rather than a header, and never in the URL, where it would be
 * written into access logs and browser history.</p>
 *
 * <p>Kept out of {@code toString()}: a refresh token is a credential for days,
 * and a request object reaches a log far more easily than it should. The
 * length limit is far above a real token's 43 characters; it only caps what an
 * anonymous caller can make the server hash.</p>
 */
@Getter
@Setter
@ToString
@NoArgsConstructor
@AllArgsConstructor
public class RefreshTokenRequest {

    @ToString.Exclude
    @NotBlank(message = "Refresh token is required")
    @Size(max = 512, message = "Refresh token must not exceed 512 characters")
    private String refreshToken;
}
