package com.library.lms.service;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * The default payment gateway: HMAC-SHA256 over the provider's two references.
 *
 * <p><b>This is the scheme the common checkout providers use.</b> Razorpay,
 * among others, hands the browser an order id, takes the payment on its own
 * pages, and returns a payment id with a signature that is
 * {@code HMAC_SHA256(order_id + "|" + payment_id, key_secret)}. The server
 * recomputes it with the secret only it holds; a payment id invented or altered
 * by the caller does not match. That is what
 * {@link #verify(String, String, String)} does here, so pointing this at such a
 * provider is a change of key and order-creation call, not of design.</p>
 *
 * <p><b>Order creation is local</b>, which is what makes this the sandbox:
 * the order reference is generated here, unique and carrying nothing but a
 * random value, and no request leaves the machine. Everything that protects the
 * fine - the signature check, the server-side verification, the unique
 * references, the audit event - is the same code
 * {@link RazorpayPaymentGateway} runs, so a flow proved here is the flow that
 * runs against the real provider. Development and the tests use this;
 * {@code payment.gateway.provider=razorpay} selects the real one.</p>
 *
 * <p><b>The secret never leaves this class.</b> It is read from configuration,
 * used to compute a MAC, and never logged, returned, stored on a payment row or
 * put in an error message. Only the key id, which is public and identifies the
 * merchant to the provider's checkout, is given to a client.</p>
 *
 * <p><b>Signatures are compared in constant time.</b> A byte-by-byte comparison
 * that stops at the first difference tells an attacker how much of a guess was
 * right, which is enough to find a valid signature one byte at a time.</p>
 */
public class HmacPaymentGateway implements PaymentGateway {

    private final String name;

    private final String keyId;

    private final String keySecret;

    public HmacPaymentGateway(String name, String keyId, String keySecret) {
        this.name = name == null || name.isBlank() ? "hmac-sandbox" : name.trim();
        this.keyId = keyId == null ? "" : keyId.trim();
        this.keySecret = keySecret == null ? "" : keySecret.trim();
    }

    @Override
    public String name() {
        return name;
    }

    /**
     * Both halves of the credential must be present. Without the secret nothing
     * could be verified, and an unverifiable payment must never reach a fine.
     */
    @Override
    public boolean configured() {
        return !keyId.isEmpty() && !keySecret.isEmpty();
    }

    /**
     * The key id, which identifies the merchant to the provider's checkout and
     * is meant to be public. The secret has no getter, here or anywhere.
     */
    @Override
    public String keyId() {
        return keyId;
    }

    @Override
    public String createOrder(String reference, BigDecimal amount, String currency) {
        if (!configured()) {
            throw new IllegalStateException("The payment gateway is not configured");
        }

        // Random, not derived from the fine or the loan: an order reference is
        // quoted in URLs and to the provider, and must not describe anything.
        return "order_" + UUID.randomUUID().toString().replace("-", "");
    }

    @Override
    public boolean verify(String providerOrderId, String providerPaymentId, String signature) {
        if (!configured() || providerOrderId == null || providerPaymentId == null) {
            return false;
        }

        // The same check the real provider's gateway makes, from the same code:
        // what proves a payment must not depend on which gateway is configured.
        return PaymentSignatures.matches(keySecret, PaymentSignatures.payload(providerOrderId, providerPaymentId),
                signature);
    }
}
