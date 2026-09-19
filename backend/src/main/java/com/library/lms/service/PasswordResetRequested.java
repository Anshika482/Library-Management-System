package com.library.lms.service;

import java.time.LocalDateTime;

/**
 * Published when a self-service password reset token has been issued: the one
 * place the token exists outside the request that created it.
 *
 * <p><b>This is the delivery boundary.</b> Whatever eventually sends the reset
 * link - an email sender, a queue - listens for this and nothing else. Until
 * one exists, {@link PasswordResetDeliveryLog} records that nothing was sent.
 * Tests read the token from here, which is how they redeem one without an
 * inbox.</p>
 *
 * <p>Listeners should run after the transaction commits, so a link is never
 * sent for a token that was rolled back.</p>
 *
 * <p>{@code toString()} names neither the token nor the address, so the event
 * cannot carry either into a log line.</p>
 *
 * @param userId    the account the token resets
 * @param email     where the link is to go
 * @param token     the token itself - the only copy there is
 * @param expiresAt when it stops working
 */
public record PasswordResetRequested(Long userId, String email, String token, LocalDateTime expiresAt) {

    @Override
    public String toString() {
        return "PasswordResetRequested[userId=" + userId + ", email=<redacted>, token=<redacted>, expiresAt="
                + expiresAt + "]";
    }
}
