package com.library.lms.config;

import java.util.Locale;

import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * Refuses to start a production instance whose assistant is the script.
 *
 * <p><b>Why this is worth failing over.</b> The scripted assistant answers from
 * a keyword list. It is honest about not knowing things, so nobody is misled
 * the way a sandbox payment misleads - but a deployment left on it has an
 * assistant in name only, answering "I cannot answer that yet" to most of what
 * anyone asks, and nothing inside the application reports that as a fault.
 * Every request succeeds. Startup is the one place the difference can be
 * noticed, so that is where it is caught.</p>
 *
 * <p><b>The default stays the script.</b> Development and CI have no provider
 * key and should not need one: they run on the default, and this validator only
 * exists under the prod profile. It is what stops that default from following a
 * deployment into production unnoticed.</p>
 *
 * <p><b>A key is required with it.</b> Naming the provider without one would
 * start an instance that answers every question with a 503. {@code ChatConfig}
 * already refuses that combination wherever it runs; this says the same thing
 * about the settings themselves, before anything is built, so a production
 * deployment fails on the first of the two problems it has rather than the
 * second.</p>
 *
 * <p>Only under the prod profile, and run as a {@code BeanFactoryPostProcessor}
 * for the same reason as the validators beside it: before anything reads these
 * settings. No message quotes a value - the key is one of them.</p>
 */
@Configuration
@Profile("prod")
public class ProductionChatValidator {

    static final String PROVIDER_PROPERTY = "chat.provider";

    static final String API_KEY_PROPERTY = "chat.anthropic.api-key";

    /** The one assistant a production deployment may answer with. */
    static final String REQUIRED_PROVIDER = "anthropic";

    @Bean
    static BeanFactoryPostProcessor productionChatCheck(ConfigurableEnvironment environment) {
        return beanFactory -> validate(
                environment.getProperty(PROVIDER_PROPERTY),
                environment.getProperty(API_KEY_PROPERTY));
    }

    /**
     * Requires the real assistant, named explicitly, with the key it needs.
     *
     * @throws IllegalStateException if the provider is missing, blank or
     *                               anything but {@code anthropic}, or if the
     *                               key is missing
     */
    static void validate(String provider, String apiKey) {
        String named = provider == null ? "" : provider.trim().toLowerCase(Locale.ROOT);

        if (named.isEmpty()) {
            throw new IllegalStateException(PROVIDER_PROPERTY + " is not set. Set CHAT_PROVIDER to "
                    + REQUIRED_PROVIDER + "; the scripted assistant answers from a keyword list and is meant for"
                    + " development and CI, so it must not be reached by leaving this unset in production.");
        }

        if (!REQUIRED_PROVIDER.equals(named)) {
            throw new IllegalStateException(PROVIDER_PROPERTY + " must be " + REQUIRED_PROVIDER + " in production."
                    + " The scripted assistant answers from a keyword list, and every one of its answers looks"
                    + " like a working assistant's.");
        }

        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(API_KEY_PROPERTY + " is not set. Set ANTHROPIC_API_KEY to the key for"
                    + " the assistant's provider; without it every question is answered with a 503.");
        }
    }
}
