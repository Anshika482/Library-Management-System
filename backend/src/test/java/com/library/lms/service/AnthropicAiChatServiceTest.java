package com.library.lms.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import com.anthropic.client.AnthropicClient;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.TextBlock;
import com.anthropic.services.blocking.MessageService;
import com.library.lms.entity.Role;
import com.library.lms.exception.AiChatUnavailableException;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * What this application sends to Claude, and what it does with what comes
 * back.
 *
 * <p><b>No request leaves this JVM.</b> The SDK client is a mock, which is what
 * lets these tests assert the two things that matter and would otherwise need a
 * real key: exactly what goes into the request, and that every way a provider
 * can fail ends as the same 503 with nothing of the provider's in it.</p>
 */
class AnthropicAiChatServiceTest {

    /** Test-only, never a real key. */
    private static final String API_KEY = "sk-ant-test-only-not-a-real-key";

    private static final ChatContext MEMBER =
            new ChatContext(7L, "Central Library", 21L, Role.ROLE_MEMBER);

    private static final ChatContext LIBRARIAN =
            new ChatContext(7L, "Central Library", 11L, Role.ROLE_LIBRARIAN);

    private AnthropicClient client;

    private MessageService messages;

    private AnthropicAiChatService assistant;

    @BeforeEach
    void stubTheProvider() {
        client = mock(AnthropicClient.class);
        messages = mock(MessageService.class);
        when(client.messages()).thenReturn(messages);
        assistant = new AnthropicAiChatService(client, "claude-opus-5");
    }

    /** An answer as the SDK hands it back. */
    private static Message answerOf(String... texts) {
        Message message = mock(Message.class);
        List<ContentBlock> blocks = java.util.Arrays.stream(texts)
                .map(text -> {
                    TextBlock textBlock = mock(TextBlock.class);
                    when(textBlock.text()).thenReturn(text);
                    ContentBlock block = mock(ContentBlock.class);
                    when(block.text()).thenReturn(Optional.of(textBlock));
                    return block;
                })
                .toList();
        when(message.content()).thenReturn(blocks);
        return message;
    }

    /** An answer carrying blocks that are not text - a thinking-only reply, say. */
    private static Message answerWithNoText() {
        Message message = mock(Message.class);
        ContentBlock block = mock(ContentBlock.class);
        when(block.text()).thenReturn(Optional.empty());
        when(message.content()).thenReturn(List.of(block));
        return message;
    }

    private void providerAnswers(Message message) {
        when(messages.create(any(MessageCreateParams.class))).thenReturn(message);
    }

    private void providerThrows(RuntimeException failure) {
        when(messages.create(any(MessageCreateParams.class))).thenThrow(failure);
    }

    // ---------- what is sent ----------

    @Test
    void theRequestNamesTheModelAndKeepsTheAnswerShort() {
        MessageCreateParams params = assistant.params("How do I pay a fine?", MEMBER);

        assertThat(params.model().toString()).isEqualTo("claude-opus-5");
        assertThat(params.maxTokens()).isEqualTo(AnthropicAiChatService.MAX_TOKENS);
    }

    @Test
    void theQuestionIsSentAsTheUsersOwnWords() {
        MessageCreateParams params = assistant.params("How do I pay a fine?", MEMBER);

        assertThat(params.toString()).contains("How do I pay a fine?");
    }

    @Test
    void theSystemPromptCarriesTheLibraryAndTheRoleAndNothingElseAboutTheCaller() {
        String prompt = AnthropicAiChatService.systemPrompt(MEMBER);

        assertThat(prompt)
                .contains("Central Library")
                .contains("a library member");

        assertThat(prompt)
                .as("no id, no username, no email - the model needs none of them")
                .doesNotContain("21")
                .doesNotContain("ROLE_MEMBER")
                .doesNotContain("@");
    }

    @Test
    void staffAreDescribedAsStaff() {
        assertThat(AnthropicAiChatService.systemPrompt(LIBRARIAN)).contains("staff");
        assertThat(AnthropicAiChatService.systemPrompt(MEMBER)).doesNotContain("staff member");
    }

    @Test
    void theSystemPromptForbidsInventingLibraryFactsAndOtherPeoplesData() {
        String prompt = AnthropicAiChatService.systemPrompt(MEMBER);

        assertThat(prompt)
                .contains("no library records")
                .contains("Never state a specific fine amount")
                .contains("Never say anything about another member");
    }

    @Test
    void aContextWithNoLibraryNameStillProducesAUsablePrompt() {
        String prompt = AnthropicAiChatService.systemPrompt(new ChatContext(7L, null, 21L, Role.ROLE_MEMBER));

        assertThat(prompt).contains("a library").doesNotContain("null");
    }

    @Test
    void theKeyIsNowhereInTheRequestThisClassBuilds() {
        MessageCreateParams params = assistant.params("hello", MEMBER);

        assertThat(params.toString()).doesNotContain(API_KEY);
        assertThat(AnthropicAiChatService.systemPrompt(MEMBER)).doesNotContain(API_KEY);
    }

    // ---------- what comes back ----------

    @Test
    void theAnswersTextIsReturned() {
        providerAnswers(answerOf("Bring the book back to the desk."));

        assertThat(assistant.reply("How do I return a book?", MEMBER))
                .isEqualTo("Bring the book back to the desk.");
    }

    @Test
    void severalTextBlocksAreJoined() {
        providerAnswers(answerOf("One. ", "Two."));

        assertThat(assistant.reply("hello", MEMBER)).isEqualTo("One. Two.");
    }

    // ---------- every way it can fail ----------

    @Test
    void aProviderRefusalBecomesAnUnavailableAssistant() {
        providerThrows(mock(AnthropicServiceException.class));

        assertThatThrownBy(() -> assistant.reply("hello", MEMBER))
                .isInstanceOf(AiChatUnavailableException.class);
    }

    @Test
    void aTimeoutOrConnectionFailureBecomesAnUnavailableAssistant() {
        providerThrows(new RuntimeException("timeout waiting for api.anthropic.com"));

        assertThatThrownBy(() -> assistant.reply("hello", MEMBER))
                .isInstanceOf(AiChatUnavailableException.class);
    }

    @Test
    void anAnswerWithNoTextBecomesAnUnavailableAssistant() {
        providerAnswers(answerWithNoText());

        assertThatThrownBy(() -> assistant.reply("hello", MEMBER))
                .as("a reply with nothing in it is not an answer")
                .isInstanceOf(AiChatUnavailableException.class);
    }

    @Test
    void anEmptyAnswerBecomesAnUnavailableAssistant() {
        providerAnswers(answerOf(""));

        assertThatThrownBy(() -> assistant.reply("hello", MEMBER))
                .isInstanceOf(AiChatUnavailableException.class);
    }

    @Test
    void aMissingQuestionOrContextNeverReachesTheProvider() {
        assertThatThrownBy(() -> assistant.reply(null, MEMBER)).isInstanceOf(AiChatUnavailableException.class);
        assertThatThrownBy(() -> assistant.reply("  ", MEMBER)).isInstanceOf(AiChatUnavailableException.class);
        assertThatThrownBy(() -> assistant.reply("hello", null)).isInstanceOf(AiChatUnavailableException.class);

        verify(messages, never()).create(any(MessageCreateParams.class));
    }

    @Test
    void aMissingClientNeverReachesTheProvider() {
        assertThatThrownBy(() -> new AnthropicAiChatService(null, "claude-opus-5").reply("hello", MEMBER))
                .isInstanceOf(AiChatUnavailableException.class);
    }

    // ---------- what a failure may not say ----------

    @Test
    void aFailureLogsNothingFromTheProviderAndNoKey() {
        Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);

        try {
            providerThrows(new RuntimeException(
                    "401 from api.anthropic.com: invalid x-api-key " + API_KEY + " for org acct_12345"));

            assertThatThrownBy(() -> assistant.reply("hello", MEMBER))
                    .isInstanceOf(AiChatUnavailableException.class);
        } finally {
            root.detachAppender(appender);
            appender.stop();
        }

        assertThat(appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList())
                .isNotEmpty()
                .allSatisfy(line -> assertThat(line)
                        .doesNotContain(API_KEY)
                        .doesNotContain("acct_12345")
                        .doesNotContain("invalid x-api-key"));
    }

    @Test
    void theRefusalSaysNothingAboutTheProvider() {
        providerThrows(new RuntimeException("rate limited: org acct_12345 over quota"));

        assertThatThrownBy(() -> assistant.reply("hello", MEMBER))
                .hasMessage("The assistant is unavailable right now.")
                .hasMessageNotContaining("acct_12345")
                .hasMessageNotContaining("rate");
    }

    @Test
    void theAssistantSaysWhatItIs() {
        assertThat(assistant.name()).isEqualTo("anthropic");
    }
}
