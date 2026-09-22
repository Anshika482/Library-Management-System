package com.library.lms.dto;

import java.time.LocalDateTime;

/**
 * The assistant's answer.
 *
 * <p>The answer, what produced it, and when. The question is not repeated back,
 * and nothing about the caller's account, their library's internals or the
 * security layer appears here - an answer carries only what the assistant is
 * willing to say to whoever asked.</p>
 *
 * @param reply     the answer, in plain text
 * @param assistant which assistant produced it, so a later change of provider
 *                  is visible to a client
 */
public record ChatResponse(String reply, String assistant, LocalDateTime answeredAt) {
}
