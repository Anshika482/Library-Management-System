package com.library.lms.config;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.library.lms.service.JwtService;

import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Turns a Bearer token on an incoming request into an authenticated user.
 *
 * <p>The login endpoint hands out a JWT; this filter is what makes holding one
 * mean something. It runs before the request reaches a controller, and if the
 * token checks out it puts the corresponding account into the security context
 * so the rest of the request knows who is calling.</p>
 *
 * <p><b>It never rejects a request.</b> A missing, malformed, expired or forged
 * token all end the same way: the context is left unauthenticated and the chain
 * continues. Deciding that an unauthenticated caller may not proceed is
 * authorization, which lives in the filter chain configuration and is not yet
 * switched on. Refusing here as well would put that decision in two places.</p>
 *
 * <p>{@link OncePerRequestFilter} guarantees the body below runs a single time
 * per request. A plain filter can be invoked again on an internal forward or an
 * error dispatch, which would repeat the token parse and the database read for
 * no benefit.</p>
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);

    /** The scheme prefix, trailing space included, that a Bearer header must start with. */
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;

    private final UserDetailsService userDetailsService;

    public JwtAuthenticationFilter(JwtService jwtService, UserDetailsService userDetailsService) {
        this.jwtService = jwtService;
        this.userDetailsService = userDetailsService;
    }

    /**
     * Authenticates the request if it carries a valid Bearer token.
     *
     * <p>The two conditions in the first check are both about not doing work.
     * A request with no Bearer header cannot produce a username, so there is
     * nothing to look up and the database is never touched - which matters,
     * because most requests to a public endpoint arrive with no header at all.
     * An already populated context means something earlier in the chain has
     * established who is calling, and overwriting it would discard a decision
     * this filter knows nothing about.</p>
     *
     * <p>The authorities come from the freshly loaded {@link UserDetails}, not
     * from the {@code roles} claim inside the token. The claim is signed and
     * therefore genuine, but it is also a snapshot of what was true when the
     * token was minted; a role revoked since then would still be sitting in it,
     * valid signature and all, until the token expired. Reading the account
     * again costs one query and means a permission change takes effect at
     * once.</p>
     *
     * <p>The credentials argument is null on purpose. The password was verified
     * at login and is not part of this request, so there is nothing to put
     * there and nothing to hold in memory.</p>
     *
     * <p>Every failure path is silent <i>towards the caller</i>. The catch
     * clears the context rather than assuming it was already empty and lets the
     * request continue; authorization then answers 401, and nothing about why
     * the token failed reaches the client. The reason is written to the server
     * log instead, as the exception's type name - enough to tell an expired
     * session from someone probing with forged tokens. The token itself is
     * never recorded anywhere: it is a bearer credential, and a log file is no
     * place to keep one.</p>
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        String authorizationHeader = request.getHeader(HttpHeaders.AUTHORIZATION);

        if (authorizationHeader == null || !authorizationHeader.startsWith(BEARER_PREFIX)
                || SecurityContextHolder.getContext().getAuthentication() != null) {
            filterChain.doFilter(request, response);
            return;
        }

        String token = authorizationHeader.substring(BEARER_PREFIX.length());

        try {
            String username = jwtService.extractUsername(token);
            UserDetails userDetails = userDetailsService.loadUserByUsername(username);

            UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                    userDetails, null, userDetails.getAuthorities());
            authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

            SecurityContextHolder.getContext().setAuthentication(authentication);
        } catch (JwtException | IllegalArgumentException | UsernameNotFoundException exception) {
            SecurityContextHolder.clearContext();

            // The type name says why - expired, wrong signature, wrong issuer or
            // audience, unknown account - which is what an operator needs to tell
            // an expired session from someone probing with forged tokens. The
            // token itself is never written: it is a bearer credential, and a log
            // file is not a place to keep one. Neither is the exception's own
            // message, which for a claim mismatch quotes the claim it compared.
            log.warn("Rejected bearer token on {} {} from {}: {}",
                    request.getMethod(), request.getRequestURI(), request.getRemoteAddr(),
                    exception.getClass().getSimpleName());
        }

        filterChain.doFilter(request, response);
    }
}
