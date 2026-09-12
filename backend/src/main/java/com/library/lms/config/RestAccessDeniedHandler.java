package com.library.lms.config;

import java.io.IOException;
import java.time.LocalDateTime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.library.lms.exception.GlobalExceptionHandler.ErrorResponse;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Answers an authenticated caller who lacks the authority an endpoint demands.
 *
 * <p>This is the filter-chain counterpart of the 401 entry point in
 * {@link SecurityConfig}. The entry point handles "who are you?"; this handles
 * "I know who you are, and you may not". A MEMBER calling a staff-only endpoint
 * lands here - the token is valid, the role is simply insufficient.</p>
 *
 * <p><b>Why it exists.</b> Without it, Spring Security's default
 * {@code AccessDeniedHandlerImpl} answered with a bare 403: no body, no content
 * type. Every other failure in this API - from a controller, from the global
 * exception handler, from the 401 entry point - arrives as the same
 * {@link ErrorResponse} JSON, so a client had to special-case exactly one
 * refusal, and the one it would most likely meet when a role is wrong.</p>
 *
 * <p><b>Who never reaches here.</b> An anonymous caller is not refused by this
 * class even when authorization denies them. Spring Security's
 * {@code ExceptionTranslationFilter} checks whether the current authentication
 * is anonymous and, if it is, sends the request to the authentication entry
 * point instead - so a missing, expired or forged token still produces the
 * existing 401, and only a genuinely authenticated caller can produce this
 * 403.</p>
 *
 * <p><b>Nothing is disclosed.</b> The message is a fixed sentence and the
 * {@link AccessDeniedException} argument is deliberately never read. Its text,
 * and anything a future Spring version adds to it, could name the rule that
 * refused the request, the authority it wanted, or the path; each of those tells
 * a caller exactly which role to go looking for. The refusal is recorded in the
 * server log instead - who was refused, and which method and path they asked
 * for - because a run of these is what a caller probing past their own role
 * looks like.</p>
 *
 * <p><b>Not the same 403 as a refused loan.</b> A MEMBER reading someone else's
 * transaction is refused by the <i>service</i>, which throws
 * {@code TransactionAccessDeniedException} and is answered by the global
 * exception handler. That request passes this chain and fails later, for a
 * different reason; the two paths are separate and this class does not touch
 * the other one.</p>
 *
 * <p>A dedicated class rather than a lambda bean beside the entry point, in the
 * same way {@link JwtAuthenticationFilter} is: it is small, but it is a distinct
 * piece of the security contract, and keeping it out of {@link SecurityConfig}
 * leaves that class describing rules rather than response formatting.</p>
 */
@Component
public class RestAccessDeniedHandler implements AccessDeniedHandler {

    private static final Logger log = LoggerFactory.getLogger(RestAccessDeniedHandler.class);

    /** The one message every filter-level refusal carries. */
    private static final String MESSAGE = "Access denied.";

    private final ObjectMapper objectMapper;

    /**
     * Takes Spring Boot's configured {@link ObjectMapper} rather than building
     * one, for the reason the entry point does: that instance has the Java time
     * module registered, so the {@code LocalDateTime} timestamp serialises the
     * way it does in every other response. A plain {@code new ObjectMapper()}
     * would throw on it.
     *
     * @param objectMapper the application's JSON writer
     */
    public RestAccessDeniedHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Writes the standard 403 response.
     *
     * <p>A committed response is left alone. If something upstream has already
     * started sending the reply, the status line and headers are gone and
     * writing a body would only corrupt what the client receives; Spring's own
     * default handler makes the same check.</p>
     *
     * @param request               the refused request, not read
     * @param response              where the 403 is written
     * @param accessDeniedException the refusal, deliberately never read
     * @throws IOException if the response cannot be written
     */
    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
            AccessDeniedException accessDeniedException) throws IOException {
        if (response.isCommitted()) {
            return;
        }

        // Who was refused what. The name comes from the account this request
        // authenticated as, not from anything the caller typed, and the URI is
        // logged as it arrived - still percent-encoded, so it cannot break the
        // line. A repeated pattern here is a caller probing for endpoints their
        // role does not cover.
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        log.warn("Access denied for username='{}' on {} {}",
                authentication != null ? authentication.getName() : "<none>",
                request.getMethod(), request.getRequestURI());

        ErrorResponse errorResponse = new ErrorResponse(
                HttpStatus.FORBIDDEN.value(),
                MESSAGE,
                LocalDateTime.now());

        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), errorResponse);
    }
}
