package com.library.lms.config;

import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * Refuses to let the application start without a real database password.
 *
 * <p>{@code spring.datasource.password} is declared as {@code ${DB_PASSWORD}}
 * with no fallback, so there is no longer a committed password to fall back
 * <i>to</i>. This class covers the two ways that alone is not enough.</p>
 *
 * <p><b>An empty password is not automatically rejected.</b> A MySQL account
 * created without one accepts it, so {@code DB_PASSWORD=} does not fail loudly
 * the way a wrong password does - on the wrong machine it quietly works, and
 * the application runs against a database it reached with no credential at all.
 * Startup is where that belongs, not a code review months later.</p>
 *
 * <p><b>A missing variable does not fail where you would expect.</b> This was
 * measured rather than assumed. {@code jwt.secret} is consumed through
 * {@code @Value}, and an unresolvable {@code ${JWT_SECRET}} there aborts the
 * context with "Could not resolve placeholder". {@code spring.datasource.password}
 * is not: it is bound by Spring Boot's relaxed binder into
 * {@code DataSourceProperties}, which tolerates the unresolved placeholder and
 * hands the connection pool the <i>literal text</i> {@code $&#123;DB_PASSWORD&#125;}
 * as the password. MySQL then reports "Access denied ... (using password: YES)",
 * which sends whoever reads it hunting for a wrong password rather than a
 * missing environment variable. Reading the value through
 * {@link org.springframework.core.env.Environment#getProperty(String)} here
 * instead is what turns that back into Spring's own plain "Could not resolve
 * placeholder 'DB_PASSWORD'", raised from this post-processor before the pool
 * exists.</p>
 *
 * <p>{@link #looksUnresolved(String)} is a second line rather than the one that
 * fires: with the wiring above, resolution throws before the value reaches the
 * check. It is kept because the binder demonstrably <i>does</i> pass unresolved
 * placeholders through, so a value arriving here already unsubstituted is a
 * real shape - and refusing it costs one comparison.</p>
 *
 * <p><b>Why a BeanFactoryPostProcessor and not an ordinary bean.</b> A plain
 * constructor check would run somewhere among the singletons, and the
 * connection pool and Hibernate are singletons too. Whichever ran first decided
 * the error message, and in the case that matters most - a server that
 * <i>accepts</i> the bad password - Hibernate would have connected and run its
 * schema update before the check fired. A {@code BeanFactoryPostProcessor} runs
 * during {@code invokeBeanFactoryPostProcessors}, strictly before any regular
 * singleton exists, so nothing has opened a connection by the time this
 * decides.</p>
 *
 * <p>Only emptiness and non-resolution are checked. Whether the password is
 * <i>correct</i> is the database's business, and it says so plainly by refusing
 * the connection. Unlike the JWT secret, no known-bad value is refused: the
 * fallback this property used to carry is a string somebody might legitimately
 * have chosen as their own local password, and locking a developer out of their
 * machine would be a poor trade for guarding a value that never left
 * localhost.</p>
 */
@Configuration
public class DatasourcePasswordValidator {

    /** The property whose resolved value must be a usable password. */
    static final String PASSWORD_PROPERTY = "spring.datasource.password";

    /** The environment variable it is expected to come from. */
    static final String PASSWORD_VARIABLE = "DB_PASSWORD";

    /**
     * Registers the check as a bean factory post-processor.
     *
     * <p>{@code static} on purpose. A non-static {@code @Bean} method forces the
     * enclosing configuration class to be instantiated this early in the
     * lifecycle, which Spring warns about and which would drag any future
     * dependency of this class into the same slot. Static keeps the early
     * machinery to the lambda alone.</p>
     *
     * <p>The returned processor ignores the bean factory it is handed and reads
     * the {@link ConfigurableEnvironment} instead, because the property is
     * configuration, not a bean.</p>
     *
     * @param environment the application's resolved configuration
     * @return a post-processor that fails the startup on a missing or empty
     *         database password
     */
    @Bean
    static BeanFactoryPostProcessor datasourcePasswordCheck(ConfigurableEnvironment environment) {
        return beanFactory -> validate(environment.getProperty(PASSWORD_PROPERTY));
    }

    /**
     * Throws unless the resolved password is something a database could accept.
     *
     * <p>Package-private and taking the value directly so the rule can be tested
     * on its own, without standing up a context or touching a database.</p>
     *
     * <p>Both messages name the variable and say what is wrong with it, and
     * deliberately contain no part of the value. There is nothing to disclose
     * when the value is empty or an unresolved placeholder, but the rule is
     * worth keeping literal: a message written to quote "the configured
     * password" is one edit away from printing a real one into a startup log.</p>
     *
     * @param password the resolved value of {@link #PASSWORD_PROPERTY}
     * @throws IllegalStateException if it is absent, empty, whitespace, or an
     *                               unresolved placeholder
     */
    static void validate(String password) {
        if (looksUnresolved(password)) {
            throw new IllegalStateException(
                    PASSWORD_PROPERTY + " still contains an unresolved placeholder, which means the "
                            + PASSWORD_VARIABLE + " environment variable is not set. Set it to the"
                            + " password for the configured MySQL account. It is refused here"
                            + " because the placeholder would otherwise be sent to the database as"
                            + " if it were the password, and the resulting \"Access denied\" hides"
                            + " what is actually wrong.");
        }

        if (password == null || password.isBlank()) {
            throw new IllegalStateException(
                    PASSWORD_PROPERTY + " resolved to a blank value. Set the " + PASSWORD_VARIABLE
                            + " environment variable to the password for the configured MySQL"
                            + " account. An empty password is refused rather than attempted,"
                            + " because a MySQL account with no password would accept it.");
        }
    }

    /**
     * Whether the value is a placeholder that was never substituted.
     *
     * <p>Matches the shape rather than the exact text, so it still recognises
     * the case if the property is later pointed at a differently named
     * variable.</p>
     */
    private static boolean looksUnresolved(String password) {
        return password != null && password.startsWith("${") && password.endsWith("}");
    }
}
