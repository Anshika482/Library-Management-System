package com.library.lms.service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Sends the password reset link to the account's address.
 *
 * <p><b>Nothing is sent unless a host is configured.</b> With
 * {@code spring.mail.host} blank - a development machine with no mail server,
 * or CI - the request is recorded as not delivered, by account id, and that is
 * all. The prod profile refuses to start in that state, so a production
 * deployment either sends or does not start; see {@code ProductionMailValidator}.</p>
 *
 * <p><b>After commit</b>, like the listener it replaces: a token that was
 * rolled back is never sent. The event is published on the issuing queue's
 * thread, after the request has been answered, so the time this takes is not in
 * the response and cannot say whether the address has an account.</p>
 *
 * <p><b>A failure to send changes nothing else.</b> It is caught here and
 * reported by account id, never rethrown: the caller already has a 202, the
 * stored token is already committed and stays usable, and the account holder
 * can ask again. Only the exception's type is logged - an SMTP failure message
 * quotes the recipient, and often the message itself.</p>
 *
 * <p><b>Never logged:</b> the token, the link built from it, the address, or
 * anything else the message carries. The log lines name an account id.</p>
 */
@Component
public class PasswordResetMailer {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetMailer.class);

    private static final String SUBJECT = "Reset your library account password";

    /** The expiry as the reader sees it: their own time, named so it cannot be misread. */
    private static final DateTimeFormatter EXPIRY =
            DateTimeFormatter.ofPattern("d MMMM yyyy 'at' HH:mm", Locale.ENGLISH);

    private final ObjectProvider<JavaMailSender> mailSender;

    private final String host;

    private final String from;

    private final String resetLinkBaseUrl;

    private final ZoneId zone;

    @Autowired
    public PasswordResetMailer(ObjectProvider<JavaMailSender> mailSender,
            @Value("${spring.mail.host:}") String host,
            @Value("${spring.mail.username:}") String username,
            @Value("${app.mail.from:}") String from,
            @Value("${app.reset-link-base-url:}") String resetLinkBaseUrl) {
        this(mailSender, host, username, from, resetLinkBaseUrl, ZoneId.systemDefault());
    }

    PasswordResetMailer(ObjectProvider<JavaMailSender> mailSender, String host, String username, String from,
            String resetLinkBaseUrl, ZoneId zone) {
        this.mailSender = mailSender;
        this.host = host == null ? "" : host.trim();
        this.from = senderAddress(from, username);
        this.resetLinkBaseUrl = resetLinkBaseUrl == null ? "" : resetLinkBaseUrl.trim();
        this.zone = zone;
    }

    /** {@code MAIL_FROM} when it is set, otherwise the SMTP account's own address. */
    private static String senderAddress(String from, String username) {
        String configured = from == null ? "" : from.trim();

        if (!configured.isEmpty()) {
            return configured;
        }
        return username == null ? "" : username.trim();
    }

    /**
     * Whether a reset link can actually be delivered: somewhere to send it,
     * someone to send it from, and somewhere for it to point.
     */
    boolean configured() {
        return !host.isEmpty() && !from.isEmpty() && !resetLinkBaseUrl.isEmpty();
    }

    /**
     * Sends the link, once the token it carries is committed.
     *
     * @param event the issued token and where it goes - never logged
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPasswordResetRequested(PasswordResetRequested event) {
        if (!configured()) {
            log.warn("No password reset delivery is configured; the reset for user id={} was not sent",
                    event.userId());
            return;
        }

        try {
            mailSender.getObject().send(message(event));
            log.info("Password reset link sent for user id={}", event.userId());
        } catch (RuntimeException failure) {
            // Everything the sender can throw: MailException for a refusal or an
            // unreachable server, and whatever else a mail library decides to
            // raise. None of it may escape a listener running after the answer
            // has gone. The type only is logged - an SMTP rejection quotes the
            // address it rejected, and a link that reached a log would outlive
            // the message.
            log.error("Password reset link could not be sent for user id={} ({}); the token stands and the"
                    + " account holder may ask again", event.userId(), failure.getClass().getSimpleName());
        }
    }

    private SimpleMailMessage message(PasswordResetRequested event) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(event.email());
        message.setSubject(SUBJECT);
        message.setText(body(link(event.token()), event.expiresAt().format(EXPIRY)));

        return message;
    }

    /** The reset page's address with the token on it, added to whatever query string it already has. */
    private String link(String token) {
        String separator = resetLinkBaseUrl.contains("?") ? "&" : "?";

        return resetLinkBaseUrl + separator + "token=" + URLEncoder.encode(token, StandardCharsets.UTF_8);
    }

    private String body(String link, String expiry) {
        return """
                Someone asked to reset the password for your library account.

                Open this link to choose a new one:

                %s

                The link works once, until %s (%s). If you did not ask for this, \
                nothing has changed and you can ignore this message.
                """.formatted(link, expiry, zone.getId());
    }
}
