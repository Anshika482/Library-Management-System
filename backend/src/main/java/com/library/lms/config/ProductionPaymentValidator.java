package com.library.lms.config;

import java.util.Locale;

import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * Refuses to start a production instance that would take card payments through
 * the sandbox gateway.
 *
 * <p><b>Why this is worth failing over.</b> The sandbox opens orders in process
 * and signs nothing a bank ever saw, yet every signature it produces verifies -
 * so a deployment left on it would mark fines paid, write {@code FINE_PAID}
 * audit events and tell members they had paid, while no money moved anywhere.
 * That failure is invisible from inside the application: everything succeeds.
 * The only place to catch it is startup, and the only safe answer is to
 * refuse.</p>
 *
 * <p><b>The default is the sandbox on purpose.</b> Development and CI run
 * without a merchant account, and the sandbox exercises the same flow. This
 * validator is what stops that default from following a deployment into
 * production: under the prod profile the provider must be named explicitly, and
 * must be the real one.</p>
 *
 * <p><b>Credentials are required with it.</b> Razorpay without a key id and
 * secret cannot open an order or verify a payment, so a production deployment
 * that names it must carry both. What it must not do is start, offer online
 * payment, and fail every attempt.</p>
 *
 * <p>Only under the prod profile, and run as a {@code BeanFactoryPostProcessor}
 * for the same reason as the validators beside it: before anything reads these
 * settings. No message quotes a value - the secret sits next to them.</p>
 */
@Configuration
@Profile("prod")
public class ProductionPaymentValidator {

    static final String PROVIDER_PROPERTY = "payment.gateway.provider";

    static final String KEY_ID_PROPERTY = "payment.gateway.key-id";

    static final String KEY_SECRET_PROPERTY = "payment.gateway.key-secret";

    /** The one provider a production deployment may use. */
    static final String REQUIRED_PROVIDER = "razorpay";

    @Bean
    static BeanFactoryPostProcessor productionPaymentCheck(ConfigurableEnvironment environment) {
        return beanFactory -> validate(
                environment.getProperty(PROVIDER_PROPERTY),
                environment.getProperty(KEY_ID_PROPERTY),
                environment.getProperty(KEY_SECRET_PROPERTY));
    }

    /**
     * Requires the real provider, named explicitly, with both halves of its
     * credential.
     *
     * @throws IllegalStateException if the provider is missing, blank or
     *                               anything but {@code razorpay}, or if either
     *                               credential is missing
     */
    static void validate(String provider, String keyId, String keySecret) {
        String named = provider == null ? "" : provider.trim().toLowerCase(Locale.ROOT);

        if (named.isEmpty()) {
            throw new IllegalStateException(PROVIDER_PROPERTY + " is not set. Set PAYMENT_GATEWAY_PROVIDER to "
                    + REQUIRED_PROVIDER + "; the sandbox gateway marks fines paid without any money moving, so it"
                    + " must never be reached by leaving this unset in production.");
        }

        if (!REQUIRED_PROVIDER.equals(named)) {
            throw new IllegalStateException(PROVIDER_PROPERTY + " must be " + REQUIRED_PROVIDER + " in production."
                    + " The sandbox gateway signs payments no bank ever saw, and every one of them verifies.");
        }

        if (isBlank(keyId)) {
            throw new IllegalStateException(KEY_ID_PROPERTY + " is not set. Set PAYMENT_GATEWAY_KEY_ID to the"
                    + " Razorpay key id; without it no order can be opened.");
        }

        if (isBlank(keySecret)) {
            throw new IllegalStateException(KEY_SECRET_PROPERTY + " is not set. Set PAYMENT_GATEWAY_KEY_SECRET to"
                    + " the Razorpay key secret; without it no payment can be verified.");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
