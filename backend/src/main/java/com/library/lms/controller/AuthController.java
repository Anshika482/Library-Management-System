package com.library.lms.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.library.lms.dto.LoginRequest;
import com.library.lms.service.JwtService;

import jakarta.validation.Valid;

/**
 * The authentication endpoint.
 *
 * <p>This controller checks a username and password and, when they are valid,
 * hands back a signed JWT. That token is the caller's proof of identity for
 * later requests; nothing in the application demands it yet, because no filter
 * reads it and every endpoint is still open to everyone.</p>
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    /** Longest username this class will write into a log line. */
    private static final int MAX_LOGGED_USERNAME = 64;

    private final AuthenticationManager authenticationManager;

    private final JwtService jwtService;

    public AuthController(AuthenticationManager authenticationManager, JwtService jwtService) {
        this.authenticationManager = authenticationManager;
        this.jwtService = jwtService;
    }

    /**
     * What a successful login returns.
     *
     * <p>One field: the token. Its only identity claim is the subject, the
     * username. It carries no role, because the server never trusts one - the
     * caller's authorities are reloaded from the database on every request - so
     * a client that needs the role cannot read it from this response or from
     * the token.</p>
     */
    public record LoginResponse(String token) {
    }

    /**
     * Checks credentials and issues a token.
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
        Authentication authentication;

        try {
            authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(
                            loginRequest.getUsername(), loginRequest.getPassword()));
        } catch (AuthenticationException exception) {
            // Which username was tried, and nothing else. Not the password, and
            // not whether the account exists - the type name says only how
            // Spring Security classified the failure, and the caller is still
            // told the one fixed message by the exception handler.
            log.warn("Login failed for username='{}' ({})",
                    forLog(loginRequest.getUsername()), exception.getClass().getSimpleName());
            throw exception;
        }

        UserDetails userDetails = (UserDetails) authentication.getPrincipal();
        log.info("Login succeeded for username='{}'", forLog(userDetails.getUsername()));

        return ResponseEntity.ok(new LoginResponse(jwtService.generateToken(userDetails)));
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
