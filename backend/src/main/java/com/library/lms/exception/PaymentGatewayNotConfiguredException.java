package com.library.lms.exception;

/**
 * Raised when an online payment is asked for on a deployment that has no
 * gateway credentials.
 *
 * <p>Online payment is optional: a library can take fines at the desk and
 * record them with the staff endpoint, which is unaffected. Without a key and
 * secret, though, nothing could be verified afterwards, so an order is refused
 * outright rather than opened and left unverifiable.</p>
 */
public class PaymentGatewayNotConfiguredException extends RuntimeException {

    public PaymentGatewayNotConfiguredException() {
        super("Online payment is not available.");
    }
}
