package com.library.lms.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.library.lms.exception.PaymentGatewayUnavailableException;

/**
 * Razorpay, through its Orders API.
 *
 * <p><b>The order is opened on the server.</b> {@code POST /v1/orders} is
 * called here, with the merchant credentials in an HTTP Basic header, and the
 * order id it returns is what the browser takes to Razorpay's checkout. Opening
 * an order in the browser instead would let a payer name their own amount.</p>
 *
 * <p><b>The amount goes in paise</b>, the smallest unit, as Razorpay requires:
 * a fine of 12.50 is sent as 1250. Rounded half-up at two decimals first, so no
 * fraction of a paisa is silently dropped or invented.</p>
 *
 * <p><b>Verification is unchanged.</b> Razorpay returns
 * {@code razorpay_signature} = HMAC-SHA256 of
 * {@code razorpay_order_id + "|" + razorpay_payment_id} under the key secret,
 * which is exactly what {@link PaymentSignatures} checks - the same check the
 * sandbox gateway uses, so switching provider does not change what proves a
 * payment.</p>
 *
 * <p><b>The secret is a request header and nothing else.</b> It is never
 * logged, never put in a URL or a query string, never stored on a payment row
 * and never returned. Nor is Razorpay's response body logged: an error from a
 * provider tends to quote back what it was sent.</p>
 *
 * <p><b>No card data passes through here.</b> The card is entered on Razorpay's
 * checkout; this class sends an amount and receives an order id.</p>
 */
public class RazorpayPaymentGateway implements PaymentGateway {

    /** The provider name stored on every payment this gateway opens. */
    private static final String NAME = "razorpay";

    /** The name {@code payment.gateway.provider} must carry to select this gateway. */
    public static String providerName() {
        return NAME;
    }

    private static final Logger log = LoggerFactory.getLogger(RazorpayPaymentGateway.class);

    /** Razorpay's own cap on the receipt field. */
    private static final int MAX_RECEIPT_LENGTH = 40;

    private final RestClient restClient;

    private final String keyId;

    private final String keySecret;

    public RazorpayPaymentGateway(RestClient.Builder restClientBuilder, String baseUrl, String keyId,
            String keySecret) {
        this.keyId = keyId == null ? "" : keyId.trim();
        this.keySecret = keySecret == null ? "" : keySecret.trim();
        this.restClient = restClientBuilder
                .baseUrl(baseUrl == null || baseUrl.isBlank() ? "https://api.razorpay.com" : baseUrl.trim())
                .build();
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String keyId() {
        return keyId;
    }

    @Override
    public boolean configured() {
        return !keyId.isEmpty() && !keySecret.isEmpty();
    }

    /**
     * Opens a Razorpay order and returns its id.
     *
     * <p>{@code payment_capture} is not sent: capture is a merchant setting on
     * the Razorpay dashboard, and deciding it per request here would make the
     * behaviour of a payment depend on this code rather than on the account.</p>
     *
     * @throws PaymentGatewayUnavailableException if Razorpay cannot be reached,
     *                                            refuses the order, or answers
     *                                            without one
     */
    @Override
    public String createOrder(String reference, BigDecimal amount, String currency) {
        if (!configured()) {
            throw new IllegalStateException("The payment gateway is not configured");
        }

        Map<String, Object> body = Map.of(
                "amount", paise(amount),
                "currency", currency == null ? "INR" : currency.trim().toUpperCase(Locale.ROOT),
                "receipt", receipt(reference));

        Map<?, ?> answer;
        try {
            answer = restClient.post()
                    .uri("/v1/orders")
                    .header(HttpHeaders.AUTHORIZATION, basicAuth())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(Map.class);
        } catch (RestClientException failure) {
            // The type only. Razorpay's error body quotes the request back,
            // and this application's own errors must not grow a channel that
            // repeats what it sent to a provider.
            log.error("Razorpay order creation failed ({})", failure.getClass().getSimpleName());
            throw new PaymentGatewayUnavailableException();
        }

        Object orderId = answer == null ? null : answer.get("id");
        if (orderId == null || orderId.toString().isBlank()) {
            log.error("Razorpay answered without an order id");
            throw new PaymentGatewayUnavailableException();
        }

        return orderId.toString();
    }

    @Override
    public boolean verify(String providerOrderId, String providerPaymentId, String signature) {
        if (!configured() || providerOrderId == null || providerPaymentId == null) {
            return false;
        }

        return PaymentSignatures.matches(keySecret, PaymentSignatures.payload(providerOrderId, providerPaymentId),
                signature);
    }

    /** The amount in the smallest unit, which is what Razorpay's API takes. */
    static long paise(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.HALF_UP).movePointRight(2).longValueExact();
    }

    /**
     * Our own reference for the order, within Razorpay's length limit.
     *
     * <p>The loan id and nothing else: a receipt is echoed back by the provider
     * and appears on their dashboard, so it must carry nothing about the member
     * who owes the fine.</p>
     */
    private static String receipt(String reference) {
        String receipt = "loan-" + (reference == null ? "" : reference);

        return receipt.length() <= MAX_RECEIPT_LENGTH ? receipt : receipt.substring(0, MAX_RECEIPT_LENGTH);
    }

    /** The credentials, per request, built and discarded - never held as a header on a shared client. */
    private String basicAuth() {
        String credentials = keyId + ":" + keySecret;

        return "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
    }
}
