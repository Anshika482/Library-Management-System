package com.library.lms.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.library.lms.dto.ChangePasswordRequest;
import com.library.lms.dto.LoginRequest;
import com.library.lms.dto.RefreshTokenRequest;
import com.library.lms.exception.TooManyLoginAttemptsException;
import com.library.lms.service.JwtService;
import com.library.lms.service.LoginAttemptService;
import com.library.lms.service.RefreshTokenService;
import com.library.lms.service.UserService;

import jakarta.validation.Valid;

/**
 * The authentication endpoints: login, refresh, logout, and changing your own
 * password.
 *
 * <p><b>Two tokens.</b> Login returns an access token - a signed JWT, sent as a
 * Bearer header and valid for an hour by default, or for however long
 * {@code JWT_ACCESS_TOKEN_VALIDITY} says - and a refresh token, an opaque
 * random value that is exchanged for a new pair once the access token has
 * expired. The server keeps only a hash of the refresh token; see
 * {@link RefreshTokenService}.</p>
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    /** Longest username this class will write into a log line. */
    private static final int MAX_LOGGED_USERNAME = 64;

    private final AuthenticationManager authenticationManager;

    private final JwtService jwtService;

    private final LoginAttemptService loginAttemptService;

    private final UserService userService;

    private final RefreshTokenService refreshTokenService;

    private final UserDetailsService userDetailsService;

    public AuthController(AuthenticationManager authenticationManager, JwtService jwtService,
            LoginAttemptService loginAttemptService, UserService userService,
            RefreshTokenService refreshTokenService, UserDetailsService userDetailsService) {
        this.authenticationManager = authenticationManager;
        this.jwtService = jwtService;
        this.loginAttemptService = loginAttemptService;
        this.userService = userService;
        this.refreshTokenService = refreshTokenService;
        this.userDetailsService = userDetailsService;
    }

    /**
     * Changes the password of whoever is making the request.
     *
     * <p><b>It lives here rather than under {@code /api/users} because everyone
     * needs it.</b> That path requires ADMIN for every method, so a member or a
     * librarian could never reach their own password there. Only login,
     * refresh and logout are open to anonymous callers, so this route falls to
     * the chain's catch-all and requires authentication - which is exactly
     * right for a self-service credential change.</p>
     *
     * <p>Changing the password also ends every refresh session the account
     * has, so a refresh token taken before the change stops working.</p>
     *
     * <p>Whose password changes is taken from {@link Authentication}, never from
     * the body, so this cannot be pointed at another account.</p>
     *
     * <p><b>204 with no body.</b> There is nothing useful to return, and a body
     * would only be somewhere for a credential to leak into.</p>
     *
     * @param request        the current password and the replacement
     * @param authentication the account changing its own password
     * @return 204 once the change is stored
     */
    @PostMapping("/password")
    public ResponseEntity<Void> changePassword(@Valid @RequestBody ChangePasswordRequest request,
            Authentication authentication) {
        userService.changePassword(request, authentication.getName());

        return ResponseEntity.noContent().build();
    }

    /**
     * What a successful login or refresh returns.
     *
     * <p>{@code token} is the access token. Its only identity claim is the
     * subject, the username. It carries no role, because the server never
     * trusts one - the caller's authorities are reloaded from the database on
     * every request - so a client that needs the role cannot read it from this
     * response or from the token.</p>
     *
     * <p>{@code refreshToken} is what to send to {@code /api/auth/refresh} when
     * the access token expires, and to {@code /api/auth/logout}. It works once:
     * every refresh returns a new one.</p>
     *
     * <p>{@code toString()} names neither token, so a response that reaches a
     * log line cannot carry a credential into it.</p>
     */
    public record LoginResponse(String token, String refreshToken) {

        @Override
        public String toString() {
            return "LoginResponse[token=<redacted>, refreshToken=<redacted>]";
        }
    }

    /**
     * Checks credentials and issues an access token and a refresh token.
     *
     * <p>Each login starts a session of its own: logging in on a second device
     * starts a second one, and ending either leaves the other alone.</p>
     *
     * <p>The verification is unchanged and still entirely Spring Security's: a
     * {@link UsernamePasswordAuthenticationToken} carries the submitted
     * credentials to the authentication manager, which resolves the account
     * through this application's UserDetailsService and compares the password
     * against the stored BCrypt hash. No comparison is written by hand here.</p>
     *
     * <p>What changed is that the result is now used rather than discarded. A
     * successful authentication carries the account it verified as its
     * principal, and the cast to {@link UserDetails} is safe because
     * DaoAuthenticationProvider puts exactly what our UserDetailsService
     * returned into that slot. Taking the identity from the authentication
     * result rather than from the submitted request matters: a token is then
     * minted only for an account the server itself confirmed, never for a name
     * a caller typed.</p>
     *
     * <p>Nothing here logs the token, the password or the hash. A JWT is a
     * bearer credential - whoever holds it can act as that user until it
     * expires - so writing one to a log file would be as careless as logging
     * the password that earned it.</p>
     *
     * @param loginRequest the submitted credentials, validated before arrival
     * @return 200 with the signed token when the credentials match
     */
    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest loginRequest) {
        String username = loginRequest.getUsername();

        // Before the password is checked, so a blocked caller costs no BCrypt
        // work. The refusal is an ordinary authentication failure, which the
        // handler answers with the one fixed 401 - a caller cannot tell being
        // blocked from guessing wrong.
        if (loginAttemptService.isBlocked(username)) {
            log.warn("Login refused by rate limit for username='{}'", forLog(username));
            throw new TooManyLoginAttemptsException();
        }

        Authentication authentication;

        try {
            authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(
                            loginRequest.getUsername(), loginRequest.getPassword()));
        } catch (AuthenticationException exception) {
            // Counted whether or not the account exists: counting only real
            // accounts would make the block itself an oracle for which
            // usernames are real.
            loginAttemptService.recordFailure(username);

            // Which username was tried, and nothing else. Not the password, and
            // not whether the account exists - the type name says only how
            // Spring Security classified the failure, and the caller is still
            // told the one fixed message by the exception handler.
            log.warn("Login failed for username='{}' ({})",
                    forLog(username), exception.getClass().getSimpleName());
            throw exception;
        }

        // Proving the password is the best evidence the earlier failures were
        // someone mistyping rather than someone guessing.
        loginAttemptService.reset(username);

        UserDetails userDetails = (UserDetails) authentication.getPrincipal();
        log.info("Login succeeded for username='{}'", forLog(userDetails.getUsername()));

        String accessToken = jwtService.generateToken(userDetails);
        String refreshToken = refreshTokenService.issue(userDetails.getUsername());

        return ResponseEntity.ok(new LoginResponse(accessToken, refreshToken));
    }

    /**
     * Exchanges a refresh token for a new access token and a new refresh token.
     *
     * <p>Open to anonymous callers, because this is what a client calls once
     * its access token has expired: the refresh token in the body is the
     * credential. It must be live, inside its session's lifetime, and belong to
     * an account that is still enabled and unlocked.</p>
     *
     * <p><b>Every refresh token works once.</b> The one presented is used up
     * and the response carries its replacement. Presenting a used one again
     * ends the whole session, because two parties holding the same token
     * cannot both be the client it was issued to.</p>
     *
     * <p>Every refusal is the same 401 with the same sentence, so the answer
     * says nothing about whether a token ever existed, was used, expired, or
     * belongs to an account that has been disabled.</p>
     *
     * @param request the refresh token
     * @return 200 with a new access token and a new refresh token
     */
    @PostMapping("/refresh")
    public ResponseEntity<LoginResponse> refresh(@Valid @RequestBody RefreshTokenRequest request) {
        RefreshTokenService.Rotation rotation = refreshTokenService.rotate(request.getRefreshToken());
        UserDetails userDetails = userDetailsService.loadUserByUsername(rotation.username());

        return ResponseEntity.ok(new LoginResponse(jwtService.generateToken(userDetails), rotation.refreshToken()));
    }

    /**
     * Ends the session a refresh token belongs to.
     *
     * <p>Every refresh token in the session is revoked, so neither the one
     * presented nor any it was exchanged for can be used again. Open to
     * anonymous callers for the same reason as refresh.</p>
     *
     * <p><b>204 whatever the token was.</b> A token that is unknown, expired or
     * already revoked gets the same answer as a live one, so logout cannot be
     * used to test whether a token is valid, and calling it twice is
     * harmless.</p>
     *
     * <p>The access token already issued is not revoked. It is self-contained
     * and checked without a database lookup, so it stays valid until it
     * expires - at most its configured lifetime after it was minted, an hour by
     * default. A client discards it when it logs out.</p>
     *
     * @param request the refresh token of the session to end
     * @return 204
     */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@Valid @RequestBody RefreshTokenRequest request) {
        refreshTokenService.revoke(request.getRefreshToken());

        return ResponseEntity.noContent().build();
    }

    /**
     * Makes a submitted username safe to write into a log line.
     *
     * <p>The username arrives from the request body, so it is whatever the
     * caller typed. A value containing a newline would otherwise end the log
     * line and start one of the attacker's own - a forged entry, in the
     * server's own log, indistinguishable from a real one. Control characters
     * become underscores and the value is truncated, so one field cannot fill
     * the log either.</p>
     */
    private static String forLog(String username) {
        if (username == null) {
            return "<none>";
        }

        String cleaned = username.replaceAll("\\p{Cntrl}", "_");

        return cleaned.length() <= MAX_LOGGED_USERNAME
                ? cleaned
                : cleaned.substring(0, MAX_LOGGED_USERNAME) + "...";
    }
}
