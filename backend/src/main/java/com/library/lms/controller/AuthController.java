package com.library.lms.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
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

    private final AuthenticationManager authenticationManager;

    private final JwtService jwtService;

    public AuthController(AuthenticationManager authenticationManager, JwtService jwtService) {
        this.authenticationManager = authenticationManager;
        this.jwtService = jwtService;
    }

    /**
     * What a successful login returns.
     *
     * <p>One field, and deliberately still one. Authentication has just looked
     * up the account, so returning the username or role alongside the token
     * would cost nothing to implement - but the token already carries both, and
     * a client that needs them can read them from it. Repeating them here would
     * widen what this endpoint discloses without adding anything.</p>
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
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(
                        loginRequest.getUsername(), loginRequest.getPassword()));

        UserDetails userDetails = (UserDetails) authentication.getPrincipal();

        return ResponseEntity.ok(new LoginResponse(jwtService.generateToken(userDetails)));
    }
}
