package com.library.lms.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.InputStream;
import java.time.ZoneId;
import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.mock.env.MockEnvironment;

/**
 * The production time-zone rules, checked with strings and zone IDs: the JVM
 * must run in the business zone, and date-times must be stored in UTC.
 *
 * <p>Nothing here changes the JVM's own zone. The guard's wiring is exercised
 * with a mock environment against the test JVM, which the pom pins to
 * {@code Asia/Kolkata}.</p>
 */
class ProductionTimeZoneValidatorTest {

    private static final ZoneId KOLKATA = ZoneId.of("Asia/Kolkata");

    private static final String SAFE_URL = "jdbc:mysql://db.internal:3306/library_db?sslMode=VERIFY_IDENTITY";

    // ---------- the business time zone ----------

    @Test
    void theJvmInTheBusinessZoneIsAccepted() {
        assertThatCode(() -> ProductionTimeZoneValidator.validateBusinessTimeZone("Asia/Kolkata", KOLKATA))
                .doesNotThrowAnyException();
        assertThatCode(() -> ProductionTimeZoneValidator.validateBusinessTimeZone(
                " Asia/Kolkata ", ZoneId.of("Asia/Calcutta")))
                .as("an alias with the same rules - what a Windows JVM reports")
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"UTC", "Europe/London", "Asia/Dubai"})
    void aJvmInAnotherZoneStopsStartupAndSaysHowToFixIt(String jvmZone) {
        assertThatThrownBy(() -> ProductionTimeZoneValidator.validateBusinessTimeZone("Asia/Kolkata",
                ZoneId.of(jvmZone)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(jvmZone)
                .hasMessageContaining("-Duser.timezone=Asia/Kolkata");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "India", "IST+5"})
    void aMissingOrUnknownBusinessZoneStopsStartup(String configured) {
        assertThatThrownBy(() -> ProductionTimeZoneValidator.validateBusinessTimeZone(configured, KOLKATA))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(ProductionTimeZoneValidator.TIME_ZONE_PROPERTY);
    }

    @Test
    void theGuardRunsAgainstTheRealJvmZone() {
        MockEnvironment mismatched = new MockEnvironment()
                .withProperty(ProductionTimeZoneValidator.TIME_ZONE_PROPERTY, "UTC")
                .withProperty(ProductionTimeZoneValidator.CONNECTION_TIME_ZONE_PROPERTY, "UTC");

        assertThatThrownBy(() -> ProductionTimeZoneValidator.productionTimeZoneCheck(mismatched)
                .postProcessBeanFactory(new DefaultListableBeanFactory()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("The JVM runs in Asia/Kolkata");

        MockEnvironment matching = new MockEnvironment()
                .withProperty(ProductionTimeZoneValidator.TIME_ZONE_PROPERTY, "Asia/Kolkata")
                .withProperty(ProductionTimeZoneValidator.CONNECTION_TIME_ZONE_PROPERTY, "UTC")
                .withProperty(ProductionDatasourceValidator.URL_PROPERTY, SAFE_URL);

        assertThatCode(() -> ProductionTimeZoneValidator.productionTimeZoneCheck(matching)
                .postProcessBeanFactory(new DefaultListableBeanFactory()))
                .doesNotThrowAnyException();
    }

    @Test
    void theGuardExistsOnlyInProduction() {
        assertThat(ProductionTimeZoneValidator.class.getAnnotation(Profile.class).value()).containsExactly("prod");
    }

    // ---------- UTC storage ----------

    @ParameterizedTest
    @ValueSource(strings = {"UTC", "Etc/UTC", "GMT", "Z"})
    void aUtcConnectionIsAccepted(String zone) {
        assertThatCode(() -> ProductionTimeZoneValidator.validateUtcStorage(SAFE_URL, zone))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "LOCAL", "SERVER", "Asia/Kolkata", "+05:30"})
    void aConnectionZoneOtherThanUtcStopsStartup(String zone) {
        assertThatThrownBy(() -> ProductionTimeZoneValidator.validateUtcStorage(SAFE_URL, zone))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(ProductionTimeZoneValidator.CONNECTION_TIME_ZONE_PROPERTY);
        assertThatThrownBy(() -> ProductionTimeZoneValidator.validateUtcStorage(SAFE_URL, null))
                .isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"serverTimezone=UTC", "connectionTimeZone=Etc%2FUTC", "SERVERTIMEZONE=UTC",
            "preserveInstants=true", "serverTimezone=UTC&connectionTimeZone=UTC"})
    void aUrlThatAgreesWithUtcStorageIsAccepted(String parameters) {
        assertThatCode(() -> ProductionTimeZoneValidator.validateUtcStorage(SAFE_URL + "&" + parameters, "UTC"))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"serverTimezone=Asia/Kolkata", "connectionTimeZone=LOCAL", "connectiontimezone=SERVER",
            "serverTimezone=IST", "serverTimezone=UTC&serverTimezone=Asia/Kolkata", "preserveInstants=false"})
    void aUrlThatContradictsUtcStorageStopsStartup(String parameters) {
        assertThatThrownBy(() -> ProductionTimeZoneValidator.validateUtcStorage(SAFE_URL + "&" + parameters, "UTC"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(ProductionDatasourceValidator.URL_PROPERTY);
    }

    @Test
    void noRefusalQuotesTheUrl() {
        String url = "jdbc:mysql://secret-host.internal:3306/secret_db?password=leaked&serverTimezone=Asia/Kolkata";

        assertThatThrownBy(() -> ProductionTimeZoneValidator.validateUtcStorage(url, "UTC"))
                .hasMessageNotContaining("secret-host.internal")
                .hasMessageNotContaining("secret_db")
                .hasMessageNotContaining("leaked");
    }

    // ---------- the shipped configuration ----------

    @Test
    void theConfigurationDeclaresTheBusinessZoneAndUtcStorage() throws Exception {
        Properties development = new Properties();
        try (InputStream file = ProductionTimeZoneValidatorTest.class.getResourceAsStream("/application.properties")) {
            assertThat(file).isNotNull();
            development.load(file);
        }

        assertThat(development.getProperty(ProductionTimeZoneValidator.TIME_ZONE_PROPERTY))
                .isEqualTo("${APP_TIME_ZONE:Asia/Kolkata}");
        assertThat(development.getProperty(ProductionTimeZoneValidator.CONNECTION_TIME_ZONE_PROPERTY))
                .isEqualTo("UTC");
        assertThatCode(() -> ProductionTimeZoneValidator.validateUtcStorage(
                development.getProperty("spring.datasource.url"), "UTC"))
                .as("the development URL agrees with the storage convention")
                .doesNotThrowAnyException();
    }
}
