package com.library.lms.exception;

/**
 * Raised when the payment provider cannot be reached, refuses to open an order,
 * or answers with something that is not one.
 *
 * <p>Distinct from {@code PaymentGatewayNotConfiguredException}: there the
 * deployment has no provider, here it has one that is not answering. Both are
 * 503 - nothing is wrong with the request, and the caller should try again or
 * pay at the desk - and neither message repeats anything the provider said. A
 * provider's error text quotes the request back to it, which is the last thing
 * an error response should carry.</p>
 */
public class PaymentGatewayUnavailableException extends RuntimeException {

    public PaymentGatewayUnavailableException() {
        super("Online payment is temporarily unavailable.");
    }
}
