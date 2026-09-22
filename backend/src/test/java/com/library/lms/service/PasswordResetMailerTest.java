package com.library.lms.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * What the reset mailer sends, what it does when it cannot send, and what it
 * never writes down.
 *
 * <p>No Spring context and no SMTP server: the sender is a mock, so the message
 * itself can be read field by field. {@code PasswordResetMailIntegrationTest}
 * covers the same class against a real SMTP server.</p>
 */
class PasswordResetMailerTest {

    private static final String TOKEN = "a-test-only-reset-token-value";

    private static final String ADDRESS = "member@example.invalid";

    private static final LocalDateTime EXPIRES_AT = LocalDateTime.of(2026, 9, 22, 14, 30);

    private final JavaMailSender sender = mock(JavaMailSender.class);

    private ListAppender<ILoggingEvent> appender;

    private Logger root;

    @BeforeEach
    void captureLogs() {
        root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);
    }

    @AfterEach
    void releaseLogs() {
        root.detachAppender(appender);
        appender.stop();
    }

    private PasswordResetMailer mailer(String host, String username, String from, String baseUrl) {
        @SuppressWarnings("unchecked")
        ObjectProvider<JavaMailSender> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(sender);

        return new PasswordResetMailer(provider, host, username, from, baseUrl, ZoneId.of("Asia/Kolkata"));
    }

    private PasswordResetMailer configured() {
        return mailer("smtp.example.invalid", "", "library@example.invalid",
                "https://library.example.invalid/reset-password");
    }

    private static PasswordResetRequested event() {
        return new PasswordResetRequested(42L, ADDRESS, TOKEN, EXPIRES_AT);
    }

    private SimpleMailMessage sent() {
        ArgumentCaptor<SimpleMailMessage> message = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(sender).send(message.capture());
        return message.getValue();
    }

    private List<String> loggedLines() {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    // ---------- when delivery is not configured ----------

    @Test
    void nothingIsSentWithoutAHostASenderOrALink() {
        List<PasswordResetMailer> unconfigured = List.of(
                mailer("", "", "library@example.invalid", "https://library.example.invalid/reset-password"),
                mailer("smtp.example.invalid", "", "", "https://library.example.invalid/reset-password"),
                mailer("smtp.example.invalid", "", "library@example.invalid", ""),
                mailer("  ", "  ", "  ", "  "));

        for (PasswordResetMailer mailer : unconfigured) {
            assertThat(mailer.configured()).isFalse();
            mailer.onPasswordResetRequested(event());
        }

        verify(sender, never()).send(any(SimpleMailMessage.class));
        assertThat(loggedLines())
                .as("silence is reported, by account id")
                .allMatch(line -> !line.contains(ADDRESS) && !line.contains(TOKEN))
                .anyMatch(line -> line.contains("No password reset delivery is configured")
                        && line.contains("user id=42"));
    }

    @Test
    void theSmtpAccountIsTheSenderWhenNoFromAddressIsGiven() {
        mailer("smtp.example.invalid", "library@example.invalid", "", "https://library.example.invalid/reset")
                .onPasswordResetRequested(event());

        assertThat(sent().getFrom()).isEqualTo("library@example.invalid");
    }

    // ---------- what is sent ----------

    @Test
    void theMessageCarriesTheLinkItsExpiryAndNothingElseAboutTheAccount() {
        configured().onPasswordResetRequested(event());

        SimpleMailMessage message = sent();
        assertThat(message.getTo()).containsExactly(ADDRESS);
        assertThat(message.getFrom()).isEqualTo("library@example.invalid");
        assertThat(message.getSubject()).isEqualTo("Reset your library account password");
        assertThat(message.getText())
                .contains("https://library.example.invalid/reset-password?token=" + TOKEN)
                .contains("22 September 2026 at 14:30")
                .contains("Asia/Kolkata")
                .doesNotContain("42");
    }

    @Test
    void aBaseUrlThatAlreadyAsksSomethingKeepsItsQueryString() {
        mailer("smtp.example.invalid", "", "library@example.invalid",
                "https://library.example.invalid/reset?lang=en").onPasswordResetRequested(event());

        assertThat(sent().getText()).contains("https://library.example.invalid/reset?lang=en&token=" + TOKEN);
    }

    @Test
    void aTokenIsEncodedIntoTheLinkRatherThanPastedIntoIt() {
        PasswordResetRequested awkward = new PasswordResetRequested(42L, ADDRESS, "a+b/c=d&e", EXPIRES_AT);

        configured().onPasswordResetRequested(awkward);

        assertThat(sent().getText())
                .as("a query string cannot be grown by what the token happens to contain")
                .contains("token=a%2Bb%2Fc%3Dd%26e")
                .doesNotContain("token=a+b/c=d&e");
    }

    // ---------- when the send fails ----------

    @Test
    void aFailureToSendIsSwallowedSoTheAnswerAndTheTokenStand() {
        doThrow(new MailSendException("550 5.1.1 <" + ADDRESS + "> unknown recipient"))
                .when(sender).send(any(SimpleMailMessage.class));

        assertThatCode(() -> configured().onPasswordResetRequested(event()))
                .as("the caller already has its 202 and the token is already committed")
                .doesNotThrowAnyException();
    }

    @Test
    void aFailureIsReportedWithoutQuotingWhatItWasSending() {
        doThrow(new MailSendException("550 5.1.1 <" + ADDRESS + "> unknown recipient"))
                .when(sender).send(any(SimpleMailMessage.class));

        configured().onPasswordResetRequested(event());

        assertThat(loggedLines())
                .anyMatch(line -> line.contains("could not be sent for user id=42"))
                .allSatisfy(line -> assertThat(line)
                        .doesNotContain(ADDRESS)
                        .doesNotContain(TOKEN)
                        .doesNotContain("550"));
    }

    // ---------- what reaches the log on the way through ----------

    @Test
    void aSuccessfulSendNamesTheAccountAndNothingItSent() {
        configured().onPasswordResetRequested(event());

        assertThat(loggedLines())
                .anyMatch(line -> line.contains("Password reset link sent for user id=42"))
                .allSatisfy(line -> assertThat(line)
                        .doesNotContain(ADDRESS)
                        .doesNotContain(TOKEN)
                        .doesNotContain("reset-password?token="));
    }
}
