package com.library.lms.config;

import java.time.Duration;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.Timeout;
import com.library.lms.service.AiChatService;
import com.library.lms.service.AnthropicAiChatService;
import com.library.lms.service.ScriptedAiChatService;

/**
 * Chooses which assistant answers questions.
 *
 * <p>One bean, picked from {@code chat.provider}:</p>
 * <ul>
 *   <li>{@code scripted} - the default: keyword-matched answers from a fixed
 *       script, no provider, no network call and no key. Development and the
 *       tests run on this, so CI needs no credential and no request ever leaves
 *       the machine.</li>
 *   <li>{@code anthropic} - Claude, through the official SDK, with the key from
 *       {@code ANTHROPIC_API_KEY}.</li>
 * </ul>
 *
 * <p><b>An unknown name stops startup</b> rather than falling back. A typo in
 * the provider must not leave a deployment quietly answering with the script
 * while everyone believes an assistant is running.</p>
 *
 * <p><b>Naming the provider without a key also stops startup.</b> An assistant
 * that cannot reach its provider answers every question with a 503, which is
 * worse than being told at deploy time that the key is missing.</p>
 *
 * <p><b>The key is read here and goes straight into the SDK client.</b> It is
 * never logged, never stored, never returned and never held anywhere this
 * application can reach it again - the startup line below says whether a key is
 * present, never what it is.</p>
 *
 * <p><b>Timeouts are explicit.</b> The SDK's own default is ten minutes, which
 * is far too long for a request a member is waiting on: a provider that stops
 * answering would hold the request thread until it gave up. These bound it.</p>
 */
@Configuration
public class ChatConfig {

    static final String SCRIPTED = "scripted";

    static final String ANTHROPIC = "anthropic";

    static final String PROVIDER_PROPERTY = "chat.provider";

    static final String CONNECT_TIMEOUT_PROPERTY = "chat.anthropic.connect-timeout";

    static final String READ_TIMEOUT_PROPERTY = "chat.anthropic.read-timeout";

    static final String REQUEST_TIMEOUT_PROPERTY = "chat.anthropic.request-timeout";

    private static final Logger log = LoggerFactory.getLogger(ChatConfig.class);

    @Bean
    AiChatService aiChatService(
            @Value("${chat.provider:" + SCRIPTED + "}") String provider,
            @Value("${chat.anthropic.api-key:}") String apiKey,
            @Value("${chat.anthropic.model:claude-opus-5}") String model,
            @Value("${chat.anthropic.connect-timeout:PT5S}") String connectTimeout,
            @Value("${chat.anthropic.read-timeout:PT30S}") String readTimeout,
            @Value("${chat.anthropic.request-timeout:PT45S}") String requestTimeout) {
        String name = provider == null || provider.isBlank()
                ? SCRIPTED
                : provider.trim().toLowerCase(Locale.ROOT);

        if (SCRIPTED.equals(name)) {
            log.info("Assistant: provider='{}' - answers from a fixed script, no provider is called", SCRIPTED);
            return new ScriptedAiChatService();
        }

        if (ANTHROPIC.equals(name)) {
            Timeout timeout = timeouts(connectTimeout, readTimeout, requestTimeout);
            requireKey(apiKey);

            // Whether a key is present, never its value, and never its length -
            // which would say which kind of key it is.
            log.info("Assistant: provider='{}' model='{}' connectTimeout={} readTimeout={} requestTimeout={}"
                            + " key=present",
                    ANTHROPIC, model, timeout.connect(), timeout.read(), timeout.request());

            return new AnthropicAiChatService(anthropicClient(apiKey, timeout), model);
        }

        throw new IllegalStateException(PROVIDER_PROPERTY + " is not an assistant this application has. Set"
                + " CHAT_PROVIDER to " + ANTHROPIC + ", or to " + SCRIPTED + " for local development and CI.");
    }

    /** The SDK client, with every timeout bounded. */
    private static AnthropicClient anthropicClient(String apiKey, Timeout timeout) {
        return AnthropicOkHttpClient.builder()
                .apiKey(apiKey.trim())
                .timeout(timeout)
                .build();
    }

    /**
     * The three timeouts a call to the provider is bounded by: how long to wait
     * for the connection, how long to wait for bytes once it is open, and how
     * long the whole call may take including the SDK's own retries.
     *
     * <p>Write uses the read timeout: the request body here is one short
     * question, so it is bounded by the same number without needing its own
     * setting.</p>
     *
     * @throws IllegalStateException if any of them is missing, malformed, zero
     *                               or negative
     */
    static Timeout timeouts(String connectTimeout, String readTimeout, String requestTimeout) {
        Duration connect = duration(connectTimeout, CONNECT_TIMEOUT_PROPERTY, "CHAT_CONNECT_TIMEOUT");
        Duration read = duration(readTimeout, READ_TIMEOUT_PROPERTY, "CHAT_READ_TIMEOUT");
        Duration request = duration(requestTimeout, REQUEST_TIMEOUT_PROPERTY, "CHAT_REQUEST_TIMEOUT");

        return Timeout.builder()
                .connect(connect)
                .read(read)
                .write(read)
                .request(request)
                .build();
    }

    private static void requireKey(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("chat.anthropic.api-key is not set. Set ANTHROPIC_API_KEY to the key"
                    + " for the assistant's provider, or set CHAT_PROVIDER to " + SCRIPTED + " to answer from the"
                    + " script instead.");
        }
    }

    /**
     * One configured timeout, as a duration, or a refusal naming the setting.
     *
     * <p>Parsed here rather than bound straight to a {@code Duration}, for the
     * reason {@code PaymentGatewayConfig} gives: the conversion that would do
     * that is registered by the application rather than the container, so
     * binding works in the running application and not wherever this
     * configuration is exercised on its own.</p>
     */
    static Duration duration(String configured, String property, String variable) {
        Duration timeout;
        try {
            timeout = DurationStyle.detectAndParse(configured);
        } catch (RuntimeException malformed) {
            throw new IllegalStateException(property + " is not a duration. Set " + variable
                    + " to an ISO-8601 duration such as PT30S, or a shorthand such as 30s.");
        }

        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalStateException(property + " must be a positive duration. Set " + variable
                    + " to something above zero; a caller is waiting on this request.");
        }

        return timeout;
    }
}
