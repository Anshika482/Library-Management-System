package com.library.lms.exception;

/**
 * Raised when a payment's answer does not verify: the signature is not the
 * provider's over those references, the order is not this library's, or the
 * references do not belong together.
 *
 * <p>One exception for every one of those, and one fixed message. Which part
 * did not match is exactly what someone forging a payment wants to know, and
 * saying so would let them find a working combination one field at a time. The
 * fine is left alone and the attempt is recorded as a failed payment.</p>
 */
public class PaymentVerificationFailedException extends RuntimeException {

    public PaymentVerificationFailedException() {
        super("The payment could not be verified.");
    }
}
