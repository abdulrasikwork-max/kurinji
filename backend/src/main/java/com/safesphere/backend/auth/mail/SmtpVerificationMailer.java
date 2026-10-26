package com.safesphere.backend.auth.mail;

import com.safesphere.backend.config.MailProperties;
import java.time.Duration;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSenderImpl;

/**
 * V25: verification e-mails over SMTP (any provider: a transactional e-mail service's SMTP relay in production, a local
 * sink in development). Credentials come only from app.mail.* (environment / git-ignored .env). A failure is logged with
 * the exception class only: never the address, the link or the token.
 */
public class SmtpVerificationMailer implements VerificationMailer {

    private static final Logger log = LoggerFactory.getLogger(SmtpVerificationMailer.class);
    private static final int TIMEOUT_MILLIS = 10_000;

    private final JavaMailSenderImpl sender = new JavaMailSenderImpl();
    private final String from;

    public SmtpVerificationMailer(MailProperties properties) {
        sender.setHost(properties.host());
        sender.setPort(properties.port());
        if (!properties.username().isEmpty()) {
            sender.setUsername(properties.username());
            sender.setPassword(properties.password());
        }
        Properties javaMail = sender.getJavaMailProperties();
        javaMail.put("mail.transport.protocol", "smtp");
        javaMail.put("mail.smtp.auth", String.valueOf(!properties.username().isEmpty()));
        javaMail.put("mail.smtp.starttls.enable", String.valueOf(properties.starttls()));
        javaMail.put("mail.smtp.starttls.required", String.valueOf(properties.starttls()));
        javaMail.put("mail.smtp.connectiontimeout", String.valueOf(TIMEOUT_MILLIS));
        javaMail.put("mail.smtp.timeout", String.valueOf(TIMEOUT_MILLIS));
        javaMail.put("mail.smtp.writetimeout", String.valueOf(TIMEOUT_MILLIS));
        javaMail.put("mail.debug", "false");
        this.from = properties.from();
    }

    @Override
    public boolean sendVerification(String toEmail, String link, Duration lifetime) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(toEmail);
        message.setSubject(VerificationEmailText.SUBJECT);
        message.setText(VerificationEmailText.body(link, lifetime));
        try {
            sender.send(message);
            return true;
        } catch (MailException | IllegalStateException e) {
            log.warn("Verification e-mail not sent ({})", e.getClass().getSimpleName());
            return false;
        }
    }

    @Override
    public boolean configured() {
        return true;
    }
}
