package com.library.lms.dto;

import java.math.BigDecimal;

/**
 * An order a client can take to the provider's checkout.
 *
 * <p>Everything here is meant to be seen by the payer: the provider's order
 * reference, what is owed, and the merchant key that identifies this library to
 * the provider's checkout. The gateway secret is not here, and has no route to
 * any response - it exists only to verify what comes back.</p>
 *
 * @param paymentId  this application's own payment row, for the verification call
 * @param keyId      the provider's public merchant key
 * @param loanId     the loan this settles
 */
public record PaymentOrderResponse(Long paymentId, String provider, String providerOrderId, String keyId,
        BigDecimal amount, String currency, Long loanId) {
}
