package com.library.lms.config;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * Refuses to start a production instance whose JVM would not keep the library's
 * business day, or whose database connection would not store date-times the way
 * existing rows were stored.
 *
 * <p><b>The business day follows the JVM's default time zone.</b> Issue dates,
 * due-date checks, overdue status and fines all ask the JVM for today's date, so
 * a JVM left in another zone - a container's default UTC, say - moves midnight.
 * {@code app.time-zone} (APP_TIME_ZONE) names the zone the library works in, and
 * the JVM must run in it. Zones are compared by their rules, so an alias such as
 * {@code Asia/Calcutta} is accepted for {@code Asia/Kolkata}.</p>
 *
 * <p><b>DATETIME columns hold UTC.</b> The MySQL driver converts between the JVM
 * zone and UTC, and every existing row was written that way. A connection in any
 * other zone would store new values shifted against the old ones, so the
 * connection zone must be UTC and the URL must not say otherwise.</p>
 *
 * <p>Only under the prod profile, and run as a {@code BeanFactoryPostProcessor}
 * for the same reason as {@link ProductionDatasourceValidator}: before the
 * connection pool exists. No message quotes the URL.</p>
 */
@Configuration
@Profile("prod")
public class ProductionTimeZoneValidator {

    static final String TIME_ZONE_PROPERTY = "app.time-zone";

    static final String CONNECTION_TIME_ZONE_PROPERTY =
            "spring.datasource.hikari.data-source-properties.connectionTimeZone";

    private static final String UTC_REASON =
            "every DATETIME column holds UTC, and existing rows were written that way.";

    @Bean
    static BeanFactoryPostProcessor productionTimeZoneCheck(ConfigurableEnvironment environment) {
        return beanFactory -> {
            validateBusinessTimeZone(environment.getProperty(TIME_ZONE_PROPERTY), ZoneId.systemDefault());
            validateUtcStorage(environment.getProperty(ProductionDatasourceValidator.URL_PROPERTY),
                    environment.getProperty(CONNECTION_TIME_ZONE_PROPERTY));
        };
    }

    /**
     * Requires the JVM to run in the configured business time zone.
     *
     * @throws IllegalStateException if the zone is missing, not a zone, or not
     *                               the JVM's
     */
    static void validateBusinessTimeZone(String configured, ZoneId jvmZone) {
        if (configured == null || configured.isBlank()) {
            throw new IllegalStateException(TIME_ZONE_PROPERTY + " is not set. Set APP_TIME_ZONE to the zone"
                    + " the library's business day follows, such as Asia/Kolkata.");
        }

        ZoneId expected;
        try {
            expected = ZoneId.of(configured.trim());
        } catch (DateTimeException exception) {
            throw new IllegalStateException(TIME_ZONE_PROPERTY + " is not a valid time zone ID. Set"
                    + " APP_TIME_ZONE to a region ID such as Asia/Kolkata.");
        }

        if (!expected.getRules().equals(jvmZone.getRules())) {
            throw new IllegalStateException("The JVM runs in " + jvmZone.getId() + " but " + TIME_ZONE_PROPERTY
                    + " is " + expected.getId() + ". Due dates, overdue status and fines follow the JVM's"
                    + " date, so start the JVM in the same zone: -Duser.timezone=" + expected.getId() + ".");
        }
    }

    /**
     * Requires the database connection to store date-times in UTC.
     *
     * @throws IllegalStateException if the connection zone is not UTC, or the
     *                               URL sets a non-UTC zone or turns the
     *                               conversion off
     */
    static void validateUtcStorage(String url, String connectionTimeZone) {
        if (!isUtc(connectionTimeZone)) {
            throw new IllegalStateException(CONNECTION_TIME_ZONE_PROPERTY + " must be UTC: " + UTC_REASON);
        }

        if (url == null) {
            return;
        }

        for (String parameter : new String[] {"connectionTimeZone", "serverTimezone"}) {
            for (String value : parameterValues(url, parameter)) {
                if (!isUtc(value)) {
                    throw new IllegalStateException(ProductionDatasourceValidator.URL_PROPERTY + " sets "
                            + parameter + " to something other than UTC. Remove it or set it to UTC: " + UTC_REASON);
                }
            }
        }

        for (String value : parameterValues(url, "preserveInstants")) {
            if ("false".equalsIgnoreCase(value.trim())) {
                throw new IllegalStateException(ProductionDatasourceValidator.URL_PROPERTY
                        + " sets preserveInstants=false, which stores date-times without converting them to UTC."
                        + " Remove it: " + UTC_REASON);
            }
        }
    }

    private static boolean isUtc(String zone) {
        if (zone == null || zone.isBlank()) {
            return false;
        }

        try {
            return ZoneId.of(zone.trim()).normalized().equals(ZoneOffset.UTC);
        } catch (DateTimeException exception) {
            return false;
        }
    }

    /** Every value the URL's query string gives this parameter, matched case-insensitively. */
    private static List<String> parameterValues(String url, String parameter) {
        List<String> values = new ArrayList<>();
        int query = url.indexOf('?');
        if (query < 0) {
            return values;
        }

        for (String pair : url.substring(query + 1).split("&")) {
            int equals = pair.indexOf('=');
            String name = (equals < 0 ? pair : pair.substring(0, equals)).trim();
            if (!name.equalsIgnoreCase(parameter)) {
                continue;
            }

            String raw = equals < 0 ? "" : pair.substring(equals + 1);
            try {
                values.add(URLDecoder.decode(raw, StandardCharsets.UTF_8));
            } catch (IllegalArgumentException malformed) {
                values.add("");
            }
        }

        return values;
    }
}
