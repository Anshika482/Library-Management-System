package com.library.lms.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * What a production instance must know before it may answer questions.
 *
 * <p>The rule: the assistant must be the real one, named explicitly, with the
 * key it needs. The scripted assistant is the default everywhere else, and this
 * is what stops that default from reaching production, where an assistant that
 * answers "I cannot answer that yet" to everything looks exactly like one that
 * is working.</p>
 *
 * <p>Called directly rather than through a context, like the validators beside
 * it: what matters is the rule, not the wiring.</p>
 */
class ProductionChatValidatorTest {

    /** Test-only, never a real key. */
    private static final String API_KEY = "sk-ant-test-only-not-a-real-key";

    @Test
    void anthropicWithAKeyIsAccepted() {
        assertThatCode(() -> ProductionChatValidator.validate("anthropic", API_KEY)).doesNotThrowAnyException();
    }

    @Test
    void theProviderIsReadWhateverItsCaseOrSpacing() {
        assertThatCode(() -> ProductionChatValidator.validate("  AnThRoPiC  ", API_KEY))
                .doesNotThrowAnyException();
    }

    // ---------- the script must not follow a deployment into production ----------

    @Test
    void theScriptedAssistantIsRefused() {
        assertThatThrownBy(() -> ProductionChatValidator.validate("scripted", API_KEY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("anthropic");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void aBlankProviderIsRefusedRatherThanDefaulted(String provider) {
        assertThatThrownBy(() -> ProductionChatValidator.validate(provider, API_KEY))
                .as("the development default must not reach production by omission")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CHAT_PROVIDER");
    }

    @Test
    void anUnsetProviderIsRefused() {
        assertThatThrownBy(() -> ProductionChatValidator.validate(null, API_KEY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CHAT_PROVIDER");
    }

    @ParameterizedTest
    @ValueSource(strings = {"openai", "gpt", "claude", "none"})
    void anAssistantNobodyImplementsIsRefused(String provider) {
        assertThatThrownBy(() -> ProductionChatValidator.validate(provider, API_KEY))
                .isInstanceOf(IllegalStateException.class);
    }

    // ---------- named, but unusable ----------

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void anthropicWithoutAKeyIsRefused(String apiKey) {
        assertThatThrownBy(() -> ProductionChatValidator.validate("anthropic", apiKey))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ANTHROPIC_API_KEY");
    }

    @Test
    void anUnsetKeyIsRefused() {
        assertThatThrownBy(() -> ProductionChatValidator.validate("anthropic", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ANTHROPIC_API_KEY");
    }

    @Test
    void theProviderIsCheckedBeforeTheKey() {
        assertThatThrownBy(() -> ProductionChatValidator.validate("scripted", null))
                .as("a deployment is told about the first of its two problems, not the second")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("anthropic")
                .hasMessageNotContaining("ANTHROPIC_API_KEY");
    }

    // ---------- and nothing is quoted back ----------

    @Test
    void noMessageEverQuotesTheKey() {
        for (String[] settings : new String[][] {
                {null, API_KEY},
                {"scripted", API_KEY},
                {"anthropic", ""}}) {
            assertThatThrownBy(() -> ProductionChatValidator.validate(settings[0], settings[1]))
                    .isInstanceOf(IllegalStateException.class)
                    .satisfies(failure -> assertThat(failure.getMessage()).doesNotContain(API_KEY));
        }
    }
}
