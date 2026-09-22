package com.library.lms.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One question for the assistant.
 *
 * <p><b>One field, and it is the only thing a caller may send.</b> There is no
 * library, no user id and no role here: an answer is scoped by the account the
 * request is authenticated as, so a caller cannot ask on another library's
 * behalf by adding a field.</p>
 *
 * <p><b>Bounded.</b> A question is a line or two; a thousand characters is
 * generous for that and small enough that nothing outsized reaches a provider,
 * or a log, later.</p>
 *
 * <p>Deliberately no {@code toString()}: a question is whatever the caller
 * typed, and the object should not make it easy to write that down.</p>
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ChatRequest {

    @NotBlank(message = "A message is required")
    @Size(max = 1000, message = "A message must not exceed 1000 characters")
    private String message;
}
