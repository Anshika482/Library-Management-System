package com.library.lms.config;

import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * Refuses to start a production instance against a database configured in a
 * way that is unsafe whatever the host turns out to be.
 *
 * <p>The development settings are convenient and deliberately wrong for
 * production: TLS off, public-key retrieval on, a database created on demand,
 * the root account, and Hibernate altering the schema at startup. Moving to
 * production means overriding all of that, and the failure mode worth guarding
 * against is that somebody overrides <i>most</i> of it - a URL copied from a
 * teammate's notes still carrying {@code useSSL=false} connects perfectly well
 * and says nothing.</p>
 *
 * <p><b>Only under the prod profile.</b> {@code @Profile("prod")} means this
 * class does not exist in development or in tests, so the local database keeps
 * working exactly as before.</p>
 *
 * <p><b>It checks the settings, not the deployment.</b> Nothing here invents a
 * host, a port, a certificate or a truststore: those are the deployment's to
 * know. What it can say without knowing them is that TLS must be asked for
 * explicitly, that credentials do not belong in a URL, and that Hibernate must
 * not be allowed to alter a production schema.</p>
 *
 * <p><b>No message ever quotes the URL.</b> A JDBC URL can carry
 * {@code user=} and {@code password=} parameters, so a message that echoed it
 * to make a failure easier to read would write credentials into the startup
 * log - which is exactly one of the things this class refuses.</p>
 *
 * <p>It runs as a {@code BeanFactoryPostProcessor} for the same reason
 * {@link DatasourcePasswordValidator} does: that is before any regular
 * singleton, so the connection pool has not been built and nothing has
 * connected by the time this decides.</p>
 */
@Configuration
@Profile("prod")
public class ProductionDatasourceValidator {

    private static final Logger log = LoggerFactory.getLogger(ProductionDatasourceValidator.class);

    static final String URL_PROPERTY = "spring.datasource.url";

    static final String USERNAME_PROPERTY = "spring.datasource.username";

    static final String DDL_AUTO_PROPERTY = "spring.jpa.hibernate.ddl-auto";

    /** The sslMode values that actually require TLS. */
    private static final String[] TLS_MODES = {"sslmode=required", "sslmode=verify_ca", "sslmode=verify_identity"};

    /**
     * Registers the check.
     *
     * <p>{@code static}, so the enclosing class is not instantiated this early
     * in the lifecycle, and reading the {@link ConfigurableEnvironment} rather
     * than injected values, because these are configuration rather than beans.</p>
     *
     * @param environment the resolved configuration
     * @return a post-processor that stops an unsafe production startup
     */
    @Bean
    static BeanFactoryPostProcessor productionDatasourceCheck(ConfigurableEnvironment environment) {
        return beanFactory -> validate(
                environment.getProperty(URL_PROPERTY),
                environment.getProperty(USERNAME_PROPERTY),
                environment.getProperty(DDL_AUTO_PROPERTY));
    }

    /**
     * Applies every production rule to the configured datasource settings.
     *
     * <p>Package-private and taking the values directly, so the rules can be
     * tested without a context and without a database.</p>
     *
     * @param url      the resolved JDBC URL
     * @param username the resolved database account
     * @param ddlAuto  the resolved Hibernate schema setting
     * @throws IllegalStateException if any setting is unsafe for production
     */
    static void validate(String url, String username, String ddlAuto) {
        if (url == null || url.isBlank()) {
            throw new IllegalStateException(URL_PROPERTY + " is not set. In the prod profile it comes"
                    + " from the DB_URL environment variable, which must be the full JDBC URL for the"
                    + " production database.");
        }
        if (url.startsWith("${")) {
            throw new IllegalStateException(URL_PROPERTY + " still contains an unresolved placeholder,"
                    + " which means DB_URL is not set. It is refused here because the placeholder would"
                    + " otherwise be handed to the driver as if it were a URL.");
        }

        String setting = url.toLowerCase(Locale.ROOT);

        refuse(setting.contains("usessl=false"), "useSSL=false",
                "it turns TLS off, so the password and every row travel in the clear");
        refuse(setting.contains("allowpublickeyretrieval=true"), "allowPublicKeyRetrieval=true",
                "it lets a server in the middle supply its own key and collect the password");
        refuse(setting.contains("createdatabaseifnotexist=true"), "createDatabaseIfNotExist=true",
                "a mistyped database name would silently create an empty one and start against it");
        refuse(setting.contains("password="), "password=",
                "credentials belong in DB_PASSWORD, not in a URL that reaches logs and metrics");

        if (!requestsTls(setting)) {
            throw new IllegalStateException(URL_PROPERTY + " does not require TLS. Add sslMode=REQUIRED,"
                    + " VERIFY_CA or VERIFY_IDENTITY to DB_URL. The driver's default is PREFERRED, which"
                    + " falls back to an unencrypted connection when the server does not offer TLS.");
        }

        if (username == null || username.isBlank()) {
            throw new IllegalStateException(USERNAME_PROPERTY + " is not set. In the prod profile it"
                    + " comes from the DB_USERNAME environment variable.");
        }
        if ("root".equalsIgnoreCase(username.trim())) {
            // A warning rather than a refusal: it is the deployment's decision,
            // and refusing would strand an instance that is otherwise correct.
            log.warn("The production database account is root. An account with rights on this schema"
                    + " alone limits what a flaw in this application can reach.");
        }

        if (!"validate".equalsIgnoreCase(ddlAuto) && !"none".equalsIgnoreCase(ddlAuto)) {
            throw new IllegalStateException(DDL_AUTO_PROPERTY + " must be 'validate' or 'none' in"
                    + " production. Anything else lets Hibernate alter the schema as the application"
                    + " starts, which belongs to a migration run instead.");
        }
    }

    /** Whether the URL asks for TLS in a way the driver will not quietly drop. */
    private static boolean requestsTls(String setting) {
        for (String mode : TLS_MODES) {
            if (setting.contains(mode)) {
                return true;
            }
        }

        return setting.contains("usessl=true");
    }

    /**
     * Throws when a parameter is present, naming the parameter and why it is
     * refused - never the URL it came from.
     */
    private static void refuse(boolean present, String parameter, String reason) {
        if (present) {
            throw new IllegalStateException(URL_PROPERTY + " contains " + parameter
                    + ", which is not safe in production: " + reason + ".");
        }
    }
}
