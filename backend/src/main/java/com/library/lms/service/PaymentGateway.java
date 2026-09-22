package com.library.lms.service;

import java.math.BigDecimal;

/**
 * The payment provider, as the rest of this application sees it.
 *
 * <p><b>Two operations, because two are all a fine needs:</b> open an order for
 * an amount, and say whether the answer that came back is genuinely the
 * provider's. Everything else - the payment page, the card, the bank - happens
 * at the provider, which is the point: the card never reaches this
 * application.</p>
 *
 * <p><b>Why an interface.</b> Providers are swapped: a library changes bank, a
 * country changes rules, a sandbox becomes the real thing. Everything above
 * this line - the payment rows, the fine, the audit event, the endpoints -
 * depends on these two methods and no provider's SDK, so replacing the provider
 * is one new implementation and a configuration change.</p>
 *
 * <p><b>Verification is server-side, always.</b> Whatever the client claims was
 * paid, the fine is not marked paid until {@link #verify} says the provider
 * signed those references. An implementation must never trust a field it was
 * handed to decide that.</p>
 */
public interface PaymentGateway {

    /** The provider's name, stored on each payment so the history stays readable across a change. */
    String name();

    /**
     * The merchant key that identifies this library to the provider's checkout.
     *
     * <p>Public by design - it is sent to the browser with every order - and
     * the counterpart of the secret, which has no getter here or anywhere. Every
     * provider needs one in the client, which is why it is on the interface
     * rather than on one implementation.</p>
     */
    String keyId();

    /** Whether this gateway has the configuration it needs to be used at all. */
    boolean configured();

    /**
     * Opens an order for an amount, and returns the provider's reference for it.
     *
     * @param reference an id of ours for the thing being paid for, which the
     *                  provider may echo back - never anything secret
     * @param amount    what is owed
     * @param currency  the currency it is owed in
     * @return the provider's order reference
     * @throws com.library.lms.exception.PaymentGatewayUnavailableException if
     *         the provider cannot be reached, or answers with anything but an
     *         order
     */
    String createOrder(String reference, BigDecimal amount, String currency);

    /**
     * Whether this answer really came from the provider, unaltered.
     *
     * @param providerOrderId   the order the payment claims to settle
     * @param providerPaymentId the payment the provider says it took
     * @param signature         what the provider signed those two with
     * @return true only if the signature is the provider's own over exactly
     *         those references
     */
    boolean verify(String providerOrderId, String providerPaymentId, String signature);
}
