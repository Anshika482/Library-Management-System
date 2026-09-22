package com.library.lms.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * What a production instance must know before it can promise to deliver a
 * password reset.
 *
 * <p>The validator is called directly rather than through a context: it is a
 * {@code BeanFactoryPostProcessor} so that it runs before anything reads these
 * settings, and what matters here is the rule, not the wiring.</p>
 */
class ProductionMailValidatorTest {

    private static final String HOST = "smtp.example.invalid";

    private static final String FROM = "library@example.invalid";

    private static final String LINK = "https://library.example.invalid/reset-password";

    @Test
    void aCompleteConfigurationIsAccepted() {
        assertThatCode(() -> ProductionMailValidator.validate(HOST, "", FROM, LINK)).doesNotThrowAnyException();
    }

    @Test
    void theSmtpAccountMayStandInForTheSenderAddress() {
        assertThatCode(() -> ProductionMailValidator.validate(HOST, FROM, "", LINK)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void aMissingHostIsRefused(String host) {
        assertThatThrownBy(() -> ProductionMailValidator.validate(host, "", FROM, LINK))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MAIL_HOST");
    }

    @Test
    void anUnsetHostIsRefused() {
        assertThatThrownBy(() -> ProductionMailValidator.validate(null, null, FROM, LINK))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MAIL_HOST");
    }

    @Test
    void aMessageWithNobodyToComeFromIsRefused() {
        assertThatThrownBy(() -> ProductionMailValidator.validate(HOST, "  ", "  ", LINK))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MAIL_FROM")
                .hasMessageContaining("MAIL_USERNAME");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void aLinkWithNowhereToPointIsRefused(String link) {
        assertThatThrownBy(() -> ProductionMailValidator.validate(HOST, "", FROM, link))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("APP_RESET_LINK_BASE_URL");
    }

    @Test
    void noMessageEverQuotesWhatWasConfigured() {
        String password = "an-smtp-password-that-must-not-appear";

        for (String[] settings : new String[][] {
                {null, password, FROM, LINK},
                {HOST, null, null, LINK},
                {HOST, password, FROM, null}}) {
            assertThatThrownBy(() -> ProductionMailValidator.validate(settings[0], settings[1], settings[2],
                    settings[3]))
                    .isInstanceOf(IllegalStateException.class)
                    .satisfies(failure -> assertThat(failure.getMessage())
                            .doesNotContain(password)
                            .doesNotContain(HOST)
                            .doesNotContain(FROM)
                            .doesNotContain(LINK));
        }
    }
}
