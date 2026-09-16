package com.library.lms.exception;

import org.springframework.security.core.AuthenticationException;

/**
 * Raised when a username has failed too many logins recently and is blocked for
 * a while.
 *
 * <p><b>It extends {@link AuthenticationException} deliberately.</b> That is
 * what makes a blocked attempt indistinguishable from an ordinary one: the
 * global exception handler answers every {@code AuthenticationException} with
 * the same 401 and the same fixed sentence, so the caller cannot tell "wrong
 * password" from "right password, but you are blocked" from "no such account".
 * A dedicated status such as 429, or a message naming the block, would tell an
 * attacker which usernames are worth attacking and when to come back.</p>
 *
 * <p>The message exists for the server log alone and never reaches a client.</p>
 */
public class TooManyLoginAttemptsException extends AuthenticationException {

    public TooManyLoginAttemptsException() {
        super("Too many failed login attempts for this username");
    }
}
