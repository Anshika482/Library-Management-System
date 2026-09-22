package com.library.lms.config;

import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * Refuses to start a production instance that could not deliver a password
 * reset link.
 *
 * <p>Delivery is optional by design: with no SMTP host configured,
 * {@code PasswordResetMailer} records that the reset was not sent and stops
 * there, which is what a development machine wants. In production that same
 * silence is the worst outcome available - the endpoint answers 202, the token
 * is issued, stored and swept away when it expires, and the person waiting for
 * the link never learns that none was sent. Failing at startup says it once,
 * to whoever is deploying, instead.</p>
 *
 * <p>Three settings, because delivery needs all three: somewhere to send from,
 * somewhere to send through, and somewhere for the link to point. A message
 * with no From address is refused by most servers, so a blank {@code MAIL_FROM}
 * with a blank {@code MAIL_USERNAME} behind it is as undeliverable as a blank
 * host.</p>
 *
 * <p>Only under the prod profile, and run as a {@code BeanFactoryPostProcessor}
 * for the same reason as the validators beside it: before anything that reads
 * the settings exists. No message quotes a value - the host and the link are
 * not secret, but the password beside them is, and a validator that quotes its
 * inputs is one edit away from quoting that.</p>
 */
@Configuration
@Profile("prod")
public class ProductionMailValidator {

    static final String HOST_PROPERTY = "spring.mail.host";

    static final String USERNAME_PROPERTY = "spring.mail.username";

    static final String FROM_PROPERTY = "app.mail.from";

    static final String RESET_LINK_PROPERTY = "app.reset-link-base-url";

    @Bean
    static BeanFactoryPostProcessor productionMailCheck(ConfigurableEnvironment environment) {
        return beanFactory -> validate(
                environment.getProperty(HOST_PROPERTY),
                environment.getProperty(USERNAME_PROPERTY),
                environment.getProperty(FROM_PROPERTY),
                environment.getProperty(RESET_LINK_PROPERTY));
    }

    /**
     * Requires enough configuration to actually send a reset link.
     *
     * @throws IllegalStateException if the host, the sender address or the link
     *                               base is missing
     */
    static void validate(String host, String username, String from, String resetLinkBaseUrl) {
        if (isBlank(host)) {
            throw new IllegalStateException(HOST_PROPERTY + " is not set. Set MAIL_HOST to the SMTP server that"
                    + " delivers password reset links; without it, resets are issued and never sent.");
        }

        if (isBlank(from) && isBlank(username)) {
            throw new IllegalStateException(FROM_PROPERTY + " is not set. Set MAIL_FROM to the address password"
                    + " reset messages come from, or MAIL_USERNAME to the SMTP account to fall back to.");
        }

        if (isBlank(resetLinkBaseUrl)) {
            throw new IllegalStateException(RESET_LINK_PROPERTY + " is not set. Set APP_RESET_LINK_BASE_URL to the"
                    + " page that asks for a new password; the reset token is added to it as a query parameter.");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
