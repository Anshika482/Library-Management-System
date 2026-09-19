package com.library.lms.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Stands in for password reset delivery until one exists.
 *
 * <p>No email is sent yet. So that nobody mistakes silence for delivery, every
 * issued token is recorded here as not sent - by account id only, never with
 * the token or the address. A real sender replaces this class and listens for
 * the same event, after the same commit.</p>
 */
@Component
public class PasswordResetDeliveryLog {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetDeliveryLog.class);

    /** After commit, so a token that was rolled back is never reported - or, later, sent. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPasswordResetRequested(PasswordResetRequested event) {
        log.warn("No password reset delivery is configured; the reset for user id={} was not sent", event.userId());
    }
}
