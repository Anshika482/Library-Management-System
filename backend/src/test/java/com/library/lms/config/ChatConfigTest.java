package com.library.lms.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.anthropic.core.Timeout;
import com.library.lms.service.AiChatService;
import com.library.lms.service.AnthropicAiChatService;
import com.library.lms.service.ScriptedAiChatService;

/**
 * Which assistant a deployment ends up running.
 *
 * <p>The default matters as much as the choice: development and CI must get the
 * scripted assistant without a key, and a deployment that asks for a provider
 * must be stopped at startup if it cannot reach one - an assistant that 503s
 * every question is worse than a deployment that will not start.</p>
 */
class ChatConfigTest {

    /** Test-only, never a real key. */
    private static final String API_KEY = "sk-ant-test-only-not-a-real-key";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(ChatConfig.class);

    // ---------- which assistant ----------

    @Test
    void theScriptedAssistantIsWhatADeploymentGetsWhenItAsksForNothing() {
        runner.run(context -> assertThat(context)
                .hasSingleBean(AiChatService.class)
                .getBean(AiChatService.class)
                .isInstanceOf(ScriptedAiChatService.class));
    }

    @Test
    void theScriptedAssistantNeedsNoKey() {
        runner.withPropertyValues("chat.provider=scripted")
                .run(context -> assertThat(context).hasNotFailed()
                        .getBean(AiChatService.class)
                        .isInstanceOf(ScriptedAiChatService.class));
    }

    @Test
    void namingAnthropicWithAKeyGetsClaude() {
        runner.withPropertyValues("chat.provider=anthropic", "chat.anthropic.api-key=" + API_KEY)
                .run(context -> assertThat(context)
                        .hasSingleBean(AiChatService.class)
                        .getBean(AiChatService.class)
                        .isInstanceOf(AnthropicAiChatService.class));
    }

    @Test
    void theProviderNameIsReadWhateverItsCaseOrSpacing() {
        runner.withPropertyValues("chat.provider=  AnThRoPiC  ", "chat.anthropic.api-key=" + API_KEY)
                .run(context -> assertThat(context).getBean(AiChatService.class)
                        .isInstanceOf(AnthropicAiChatService.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"openai", "gpt", "claude", "none"})
    void anAssistantNobodyImplementsStopsStartup(String provider) {
        runner.withPropertyValues("chat.provider=" + provider)
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .hasMessageContaining("CHAT_PROVIDER"));
    }

    // ---------- naming a provider without the means to reach it ----------

    @Test
    void namingAnthropicWithNoKeyStopsStartup() {
        runner.withPropertyValues("chat.provider=anthropic")
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .hasMessageContaining("ANTHROPIC_API_KEY"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void aBlankKeyStopsStartup(String key) {
        runner.withPropertyValues("chat.provider=anthropic", "chat.anthropic.api-key=" + key)
                .run(context -> assertThat(context).hasFailed());
    }

    // ---------- how long the provider is given ----------

    @Test
    void theDefaultTimeoutsAreShortEnoughForSomeoneWaiting() {
        Timeout timeout = ChatConfig.timeouts("PT5S", "PT30S", "PT45S");

        assertThat(timeout.connect()).isEqualTo(Duration.ofSeconds(5));
        assertThat(timeout.read()).isEqualTo(Duration.ofSeconds(30));
        assertThat(timeout.request()).isEqualTo(Duration.ofSeconds(45));
        assertThat(timeout.write()).as("one short question, bounded like the read").isEqualTo(timeout.read());
    }

    @Test
    void theTimeoutsAreConfigurable() {
        Timeout timeout = ChatConfig.timeouts("1s", "2s", "3s");

        assertThat(timeout.connect()).isEqualTo(Duration.ofSeconds(1));
        assertThat(timeout.read()).isEqualTo(Duration.ofSeconds(2));
        assertThat(timeout.request()).isEqualTo(Duration.ofSeconds(3));
    }

    @Test
    void aTimeoutOfZeroOrLessIsRefusedRatherThanWaitingForEver() {
        assertThatThrownBy(() -> ChatConfig.timeouts("PT0S", "PT30S", "PT45S"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CHAT_CONNECT_TIMEOUT");
        assertThatThrownBy(() -> ChatConfig.timeouts("PT5S", "-PT1S", "PT45S"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CHAT_READ_TIMEOUT");
        assertThatThrownBy(() -> ChatConfig.timeouts("PT5S", "PT30S", "PT0S"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CHAT_REQUEST_TIMEOUT");
    }

    @Test
    void aTimeoutThatIsNotADurationIsRefused() {
        assertThatThrownBy(() -> ChatConfig.timeouts("soon", "PT30S", "PT45S"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CHAT_CONNECT_TIMEOUT");
    }

    @Test
    void aBadTimeoutStopsTheContextRatherThanBuildingAnAssistant() {
        runner.withPropertyValues(
                        "chat.provider=anthropic",
                        "chat.anthropic.api-key=" + API_KEY,
                        "chat.anthropic.read-timeout=PT0S")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void aPositiveTimeoutIsAccepted() {
        assertThatCode(() -> ChatConfig.timeouts("PT1S", "PT1S", "PT1S")).doesNotThrowAnyException();
    }

    // ---------- the key stays out of everything ----------

    @Test
    void theKeyIsNotInTheAssistantsDescription() {
        runner.withPropertyValues("chat.provider=anthropic", "chat.anthropic.api-key=" + API_KEY)
                .run(context -> {
                    AiChatService assistant = context.getBean(AiChatService.class);

                    assertThat(assistant.toString()).doesNotContain(API_KEY);
                    assertThat(assistant.name()).isEqualTo("anthropic");
                });
    }
}
