package com.library.lms.config;

import java.time.Duration;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import com.library.lms.service.HmacPaymentGateway;
import com.library.lms.service.PaymentGateway;
import com.library.lms.service.RazorpayPaymentGateway;

/**
 * Chooses which payment provider this deployment talks to.
 *
 * <p>One bean, picked from {@code payment.gateway.provider}:</p>
 * <ul>
 *   <li>{@code razorpay} - the real thing: orders are opened on Razorpay's
 *       Orders API with the merchant credentials.</li>
 *   <li>{@code hmac-sandbox} - the default: orders are opened locally, and
 *       every other part of the flow, the signature check included, is
 *       identical. Development and the tests run on this, and no request
 *       leaves the machine.</li>
 * </ul>
 *
 * <p><b>An unknown name stops startup</b> rather than falling back. A typo in
 * the provider name must not quietly leave a production deployment taking
 * payments through the sandbox, where every signature it writes verifies
 * against a secret nobody charged a card with.</p>
 *
 * <p>The credentials are read here and handed to the gateway. Neither is
 * logged: the startup line below names the provider and whether credentials are
 * present, never their values.</p>
 *
 * <p><b>The provider's client has explicit timeouts.</b> Opening an order is a
 * synchronous call made inside the transaction that writes the payment row, so
 * a provider that accepts a connection and then stops talking would hold a
 * database connection for as long as it cared to. The defaults are short
 * enough that a member waiting on a checkout gets an answer, and the 503 they
 * get instead leaves the fine payable at the desk.</p>
 */
@Configuration
public class PaymentGatewayConfig {

    static final String SANDBOX = "hmac-sandbox";

    static final String CONNECT_TIMEOUT_PROPERTY = "payment.gateway.connect-timeout";

    static final String READ_TIMEOUT_PROPERTY = "payment.gateway.read-timeout";

    private static final Logger log = LoggerFactory.getLogger(PaymentGatewayConfig.class);

    @Bean
    PaymentGateway paymentGateway(
            @Value("${payment.gateway.provider:" + SANDBOX + "}") String provider,
            @Value("${payment.gateway.key-id:}") String keyId,
            @Value("${payment.gateway.key-secret:}") String keySecret,
            @Value("${payment.gateway.base-url:https://api.razorpay.com}") String baseUrl,
            @Value("${payment.gateway.connect-timeout:PT3S}") String connectTimeout,
            @Value("${payment.gateway.read-timeout:PT8S}") String readTimeout,
            RestClient.Builder restClientBuilder) {
        // Parsed here rather than bound straight to a Duration: the conversion
        // that would do that is registered by the application, not by the
        // container, so binding would work in the running application and not
        // wherever this configuration is exercised on its own. Both ISO-8601
        // (PT3S) and the shorthand (3s) are accepted.
        Duration connect = duration(connectTimeout, CONNECT_TIMEOUT_PROPERTY, "PAYMENT_GATEWAY_CONNECT_TIMEOUT");
        Duration read = duration(readTimeout, READ_TIMEOUT_PROPERTY, "PAYMENT_GATEWAY_READ_TIMEOUT");

        RestClient.Builder timed = restClientBuilder.clone()
                .requestFactory(ClientHttpRequestFactoryBuilder.detect().build(timeouts(connect, read)));

        PaymentGateway gateway = select(provider, keyId, keySecret, baseUrl, timed);

        log.info("Payment gateway: provider='{}' credentials={} connectTimeout={} readTimeout={}",
                gateway.name(), gateway.configured() ? "present" : "absent - online payment is unavailable",
                connect, read);

        return gateway;
    }

    /**
     * The timeouts the provider's client is built with.
     *
     * <p>Neither may be zero or negative: on most clients that means "wait for
     * ever", which is the state this exists to prevent.</p>
     *
     * @throws IllegalStateException if either timeout is zero or negative
     */
    static ClientHttpRequestFactorySettings timeouts(Duration connectTimeout, Duration readTimeout) {
        requirePositive(connectTimeout, CONNECT_TIMEOUT_PROPERTY, "PAYMENT_GATEWAY_CONNECT_TIMEOUT");
        requirePositive(readTimeout, READ_TIMEOUT_PROPERTY, "PAYMENT_GATEWAY_READ_TIMEOUT");

        return ClientHttpRequestFactorySettings.defaults()
                .withConnectTimeout(connectTimeout)
                .withReadTimeout(readTimeout);
    }

    /** One configured timeout, as a duration, or a refusal naming the setting. */
    static Duration duration(String configured, String property, String variable) {
        try {
            return DurationStyle.detectAndParse(configured);
        } catch (RuntimeException malformed) {
            throw new IllegalStateException(property + " is not a duration. Set " + variable
                    + " to an ISO-8601 duration such as PT5S, or a shorthand such as 5s.");
        }
    }

    private static void requirePositive(Duration timeout, String property, String variable) {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalStateException(property + " must be a positive duration. Set " + variable
                    + " to an ISO-8601 duration such as PT5S; zero or less would wait for ever on a provider that"
                    + " stops answering.");
        }
    }

    private static PaymentGateway select(String provider, String keyId, String keySecret, String baseUrl,
            RestClient.Builder restClientBuilder) {
        String name = provider == null || provider.isBlank() ? SANDBOX : provider.trim().toLowerCase(Locale.ROOT);

        if (RazorpayPaymentGateway.providerName().equals(name)) {
            return new RazorpayPaymentGateway(restClientBuilder, baseUrl, keyId, keySecret);
        }
        if (SANDBOX.equals(name)) {
            return new HmacPaymentGateway(SANDBOX, keyId, keySecret);
        }

        throw new IllegalStateException("payment.gateway.provider is not a provider this application has. Set"
                + " PAYMENT_GATEWAY_PROVIDER to razorpay, or to " + SANDBOX + " for local development.");
    }
}
