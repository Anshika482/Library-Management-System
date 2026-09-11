package com.library.lms.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Covers the rule that decides whether the application may start with the
 * configured database password.
 *
 * <p>The rule is tested directly rather than through a context. Standing up a
 * Spring context to prove a startup failure would mean deliberately
 * misconfiguring a datasource, and a misconfigured datasource is one typo away
 * from pointing at the developer's real database. Calling the check with a
 * string proves the same thing and cannot touch MySQL at all.</p>
 *
 * <p>What a context <i>does</i> add - that the check runs early enough, before
 * the connection pool is built - is a property of the
 * {@code BeanFactoryPostProcessor} registration rather than of the rule, and is
 * verified by running the application, not here.</p>
 */
class DatasourcePasswordValidatorTest {

    // ---------- the unresolved placeholder ----------

    @Test
    void anUnresolvedPlaceholderIsRefused() {
        // DB_PASSWORD not set. Spring's relaxed binder does not fail on this
        // the way @Value does; it hands the literal text through as the
        // password, and MySQL replies "Access denied ... (using password: YES)"
        // - which sends the reader looking for a wrong password instead of a
        // missing environment variable.
        assertThatThrownBy(() -> DatasourcePasswordValidator.validate("${DB_PASSWORD}"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unresolved placeholder")
                .hasMessageContaining("DB_PASSWORD");
    }

    @Test
    void anUnresolvedPlaceholderUnderAnyVariableNameIsRefused() {
        // The rule matches the shape, so renaming the variable behind the
        // property does not quietly disable the check.
        assertThatThrownBy(() -> DatasourcePasswordValidator.validate("${SOME_OTHER_VARIABLE}"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unresolved placeholder");
    }

    // ---------- blank ----------

    @Test
    void anEmptyPasswordIsRefused() {
        // DB_PASSWORD= in the environment. Refused rather than attempted,
        // because a MySQL account created without a password accepts it and
        // the application would run with no credential at all.
        assertThatThrownBy(() -> DatasourcePasswordValidator.validate(""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("blank");
    }

    @Test
    void aWhitespaceOnlyPasswordIsRefused() {
        assertThatThrownBy(() -> DatasourcePasswordValidator.validate("   "))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("blank");
    }

    @Test
    void anAbsentPasswordIsRefused() {
        assertThatThrownBy(() -> DatasourcePasswordValidator.validate(null))
                .as("a clear startup message, not a NullPointerException")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("blank");
    }

    // ---------- accepted ----------

    @Test
    void anOrdinaryPasswordIsAccepted() {
        assertThatCode(() -> DatasourcePasswordValidator.validate("not-a-real-password"))
                .doesNotThrowAnyException();
    }

    @Test
    void aPasswordThatMerelyContainsBracesIsAccepted() {
        // Only a value that is entirely a placeholder is refused. A password
        // that happens to contain ${ or } is a legitimate password and must not
        // be mistaken for configuration that failed to resolve.
        assertThatCode(() -> DatasourcePasswordValidator.validate("has${braces}inside-it"))
                .doesNotThrowAnyException();
    }

    // ---------- nothing leaks ----------

    @Test
    void noRefusalMessageEverQuotesThePassword() {
        // A startup failure lands in a log, and logs get copied around. On a
        // misconfigured deployment the rejected value may well be a real
        // password from the wrong environment.
        assertThatThrownBy(() -> DatasourcePasswordValidator.validate("   "))
                .hasMessageNotContaining("   ");
    }
}
