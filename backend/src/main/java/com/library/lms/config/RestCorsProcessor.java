package com.library.lms.config;

import java.io.IOException;
import java.time.LocalDateTime;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.cors.DefaultCorsProcessor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.library.lms.exception.GlobalExceptionHandler.ErrorResponse;

/**
 * Refuses a cross-origin request in the API's own error shape.
 *
 * <p><b>Spring decides; this class only words the refusal.</b> Every rule -
 * whether the origin is listed, whether a preflight asks for an allowed method
 * and headers, whether the Origin header is even well formed - is applied by
 * {@link DefaultCorsProcessor} unchanged. Its own refusal is a 403 with a
 * plain-text body, the one error this API would otherwise return in a shape
 * other than {@link ErrorResponse}.</p>
 *
 * <p><b>A refused request goes no further.</b> The filter using this processor
 * stops the chain, so the request is never authenticated and never reaches a
 * controller: an unlisted site cannot have a user's browser carry out an action
 * even though it could never read the answer.</p>
 *
 * <p>The message is a fixed sentence. It names neither the origin that was
 * refused nor the ones that are allowed - the second would be a list of the
 * deployment's own sites, handed to whoever asked from somewhere else.</p>
 */
@Component
public class RestCorsProcessor extends DefaultCorsProcessor {

    static final String MESSAGE = "Cross-origin request not allowed.";

    private final ObjectMapper objectMapper;

    public RestCorsProcessor(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    protected void rejectRequest(ServerHttpResponse response) throws IOException {
        ErrorResponse errorResponse = new ErrorResponse(
                HttpStatus.FORBIDDEN.value(),
                MESSAGE,
                LocalDateTime.now());

        response.setStatusCode(HttpStatus.FORBIDDEN);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        response.getBody().write(objectMapper.writeValueAsBytes(errorResponse));
        response.flush();
    }
}
