package com.library.lms.service;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * The signature check that decides whether a payment may settle a fine.
 *
 * <p>One implementation, used by every gateway, because every gateway must
 * check the same thing the same way: HMAC-SHA256 over
 * {@code order_id + "|" + payment_id}, hex-encoded, under the merchant secret.
 * That is the scheme Razorpay documents, and the sandbox gateway follows it so
 * the two are interchangeable.</p>
 *
 * <p><b>Constant-time comparison.</b> A comparison that stops at the first
 * wrong byte tells an attacker how much of a guess was right, which is enough
 * to find a valid signature one byte at a time.</p>
 *
 * <p>Nothing here logs, and the secret is a parameter rather than state: it
 * arrives, is used, and is gone.</p>
 */
final class PaymentSignatures {

    private static final String ALGORITHM = "HmacSHA256";

    private PaymentSignatures() {
    }

    /** What the provider signs: the two references, in order, separated by a pipe. */
    static String payload(String providerOrderId, String providerPaymentId) {
        return providerOrderId + "|" + providerPaymentId;
    }

    /**
     * Whether {@code signature} is the MAC of {@code payload} under this secret.
     *
     * <p>Every unusable input - a missing secret, a null reference, something
     * that is not hex - is false rather than an exception. A signature that
     * cannot be checked has not been checked, and must never pass.</p>
     */
    static boolean matches(String secret, String payload, String signature) {
        if (secret == null || secret.isEmpty() || payload == null || signature == null) {
            return false;
        }

        byte[] presented;
        try {
            presented = HexFormat.of().parseHex(signature.trim().toLowerCase(Locale.ROOT));
        } catch (IllegalArgumentException notHex) {
            return false;
        }

        return MessageDigest.isEqual(mac(secret, payload), presented);
    }

    /** The MAC of one string under one secret, as raw bytes. */
    private static byte[] mac(String secret, String payload) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException | InvalidKeyException failure) {
            // Neither can happen with a fixed algorithm and a non-empty key, and
            // neither message may reach a caller: failing closed is the only safe
            // answer when a signature cannot be computed.
            throw new IllegalStateException("Payment signatures cannot be computed");
        }
    }
}
