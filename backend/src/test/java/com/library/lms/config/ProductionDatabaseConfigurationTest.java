package com.library.lms.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.InputStream;
import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Covers both halves of the production database configuration: the rules that
 * refuse an unsafe datasource, and the file that is actually shipped.
 *
 * <p>The rules are exercised directly, with strings. Standing up a context to
 * test them would mean pointing a real application at a deliberately broken
 * datasource, and the point of the rules is that such a thing never gets as far
 * as connecting.</p>
 *
 * <p>The second half reads {@code application-prod.properties} off the
 * classpath and checks what it says. A validator is no use if the file it
 * guards quietly ships a fallback password or leaves Hibernate altering the
 * schema, and that is a mistake an edit could introduce without any test
 * noticing.</p>
 */
class ProductionDatabaseConfigurationTest {

    private static final String SAFE_URL =
            "jdbc:mysql://db.internal:3306/library_db?sslMode=VERIFY_IDENTITY&serverTimezone=UTC";

    private static final String ACCOUNT = "library_app";

    // ---------- a correct production datasource ----------

    @Test
    void aTlsUrlWithADedicatedAccountAndValidateIsAccepted() {
        assertThatCode(() -> ProductionDatasourceValidator.validate(SAFE_URL, ACCOUNT, "validate"))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"sslMode=REQUIRED", "sslMode=VERIFY_CA", "sslMode=VERIFY_IDENTITY", "useSSL=true"})
    void everyWayOfActuallyRequiringTlsIsAccepted(String tlsSetting) {
        String url = "jdbc:mysql://db.internal:3306/library_db?" + tlsSetting;

        assertThatCode(() -> ProductionDatasourceValidator.validate(url, ACCOUNT, "validate"))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"validate", "none", "VALIDATE"})
    void onlyNonAlteringSchemaSettingsAreAccepted(String ddlAuto) {
        assertThatCode(() -> ProductionDatasourceValidator.validate(SAFE_URL, ACCOUNT, ddlAuto))
                .doesNotThrowAnyException();
    }

    // ---------- the settings that are refused ----------

    @ParameterizedTest
    @ValueSource(strings = {"useSSL=false", "allowPublicKeyRetrieval=true", "createDatabaseIfNotExist=true"})
    void theDevelopmentConveniencesAreRefused(String parameter) {
        String url = SAFE_URL + "&" + parameter;

        assertThatThrownBy(() -> ProductionDatasourceValidator.validate(url, ACCOUNT, "validate"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(parameter.split("=")[0]);
    }

    @Test
    void aUrlCarryingCredentialsIsRefused() {
        String url = SAFE_URL + "&user=library_app&password=in-the-url";

        assertThatThrownBy(() -> ProductionDatasourceValidator.validate(url, ACCOUNT, "validate"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DB_PASSWORD");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "jdbc:mysql://db.internal:3306/library_db",
            "jdbc:mysql://db.internal:3306/library_db?sslMode=DISABLED",
            "jdbc:mysql://db.internal:3306/library_db?sslMode=PREFERRED"})
    void aUrlThatDoesNotInsistOnTlsIsRefused(String url) {
        // PREFERRED is the driver's own default and the reason an explicit
        // setting is demanded: it drops to an unencrypted connection whenever
        // the server does not offer TLS, without saying so.
        assertThatThrownBy(() -> ProductionDatasourceValidator.validate(url, ACCOUNT, "validate"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sslMode");
    }

    @ParameterizedTest
    @ValueSource(strings = {"update", "create", "create-drop"})
    void lettingHibernateAlterTheSchemaIsRefused(String ddlAuto) {
        assertThatThrownBy(() -> ProductionDatasourceValidator.validate(SAFE_URL, ACCOUNT, ddlAuto))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("validate");
    }

    @Test
    void aMissingUrlIsRefused() {
        assertThatThrownBy(() -> ProductionDatasourceValidator.validate(null, ACCOUNT, "validate"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DB_URL");
    }

    @Test
    void anUnresolvedUrlPlaceholderIsRefused() {
        assertThatThrownBy(() -> ProductionDatasourceValidator.validate("${DB_URL}", ACCOUNT, "validate"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unresolved placeholder");
    }

    @Test
    void aMissingUsernameIsRefused() {
        assertThatThrownBy(() -> ProductionDatasourceValidator.validate(SAFE_URL, "  ", "validate"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DB_USERNAME");
    }

    @Test
    void rootIsAllowedButTheRulesStillApply() {
        // Refusing root would strand a deployment that is otherwise correct, so
        // it is a warning. Everything else is still enforced for it.
        assertThatCode(() -> ProductionDatasourceValidator.validate(SAFE_URL, "root", "validate"))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> ProductionDatasourceValidator.validate(SAFE_URL + "&useSSL=false", "root", "validate"))
                .isInstanceOf(IllegalStateException.class);
    }

    // ---------- no refusal describes the database ----------

    @Test
    void noMessageEverQuotesTheUrl() {
        // A JDBC URL can carry credentials, so a message that echoed it to be
        // helpful would write them into the startup log.
        String url = "jdbc:mysql://secret-host.internal:3306/secret_db?useSSL=false&password=leaked";

        assertThatThrownBy(() -> ProductionDatasourceValidator.validate(url, ACCOUNT, "validate"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("secret-host.internal")
                .hasMessageNotContaining("secret_db")
                .hasMessageNotContaining("leaked");
    }

    // ---------- the file that actually ships ----------

    @Test
    void theProductionProfileTakesEveryCredentialFromTheEnvironmentWithNoFallback() throws Exception {
        Properties production = productionProfile();

        for (String key : new String[] {
                "spring.datasource.url", "spring.datasource.username", "spring.datasource.password"}) {
            String value = production.getProperty(key);

            assertThat(value).as("%s must be set", key).isNotNull();
            assertThat(value).as("%s must come from the environment", key).startsWith("${").endsWith("}");
            assertThat(value).as("%s must not carry a fallback value", key).doesNotContain(":");
        }
    }

    @Test
    void theProductionProfileNeitherAltersTheSchemaNorLogsSql() throws Exception {
        Properties production = productionProfile();

        assertThat(production.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
        assertThat(production.getProperty("spring.jpa.show-sql")).isEqualTo("false");
        assertThat(production.getProperty("spring.jpa.properties.hibernate.format_sql")).isEqualTo("false");
        assertThat(production.getProperty("logging.level.org.hibernate.SQL")).isEqualTo("WARN");
        assertThat(production.getProperty("logging.level.org.hibernate.orm.jdbc.bind"))
                .as("bind parameters would put the data itself in the log")
                .isEqualTo("WARN");
    }

    @Test
    void theProductionProfileContainsNoLiteralCredential() throws Exception {
        Properties production = productionProfile();

        production.forEach((key, value) -> {
            String name = String.valueOf(key).toLowerCase();
            if (name.contains("password") || name.contains("secret")) {
                assertThat(String.valueOf(value))
                        .as("%s must be a placeholder, never a value", key)
                        .startsWith("${");
            }
        });
    }

    @Test
    void theProductionProfileBuildsTheSchemaWithFlywayAndHibernateOnlyValidatesIt() throws Exception {
        Properties production = productionProfile();

        assertThat(production.getProperty("spring.flyway.enabled")).isEqualTo("true");
        assertThat(production.getProperty("spring.flyway.locations")).isEqualTo("classpath:db/migration");
        assertThat(production.getProperty("spring.jpa.hibernate.ddl-auto"))
                .as("Hibernate checks the migrated schema and alters nothing")
                .isEqualTo("validate");
        assertThat(production.getProperty("spring.flyway.clean-disabled"))
                .as("clean drops every object in the schema")
                .isEqualTo("true");
        assertThat(production.getProperty("spring.flyway.baseline-on-migrate"))
                .as("an unrecognised non-empty database must stop startup, not be adopted silently")
                .isEqualTo("false");
    }

    @Test
    void theDevelopmentDefaultsLeaveFlywayOff() throws Exception {
        // The context smoke test connects to the developer's own database,
        // which ddl-auto built and which has no Flyway history. Flyway on by
        // default would stop that test - and every developer's startup - cold.
        Properties development = properties("/application.properties");

        assertThat(development.getProperty("spring.flyway.enabled")).isEqualTo("false");
        assertThat(development.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("update");
    }

    private static Properties productionProfile() throws Exception {
        return properties("/application-prod.properties");
    }

    private static Properties properties(String resource) throws Exception {
        Properties properties = new Properties();

        try (InputStream file = ProductionDatabaseConfigurationTest.class.getResourceAsStream(resource)) {
            assertThat(file).as("%s must be on the classpath", resource).isNotNull();
            properties.load(file);
        }

        return properties;
    }
}
