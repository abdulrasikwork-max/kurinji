package com.safesphere.backend.auth.mail;

import com.safesphere.backend.config.EmailVerificationProperties;
import com.safesphere.backend.config.MailProperties;
import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * V25: picks the verification mailer. With app.mail.host and app.mail.from set it is SMTP; otherwise a mailer that sends
 * nothing and says so at start-up (fail safe: an account whose e-mail could not be sent stays unverified).
 */
@Configuration
public class MailConfig {

    private static final Logger log = LoggerFactory.getLogger(MailConfig.class);

    @Bean
    public VerificationMailer verificationMailer(MailProperties mail, EmailVerificationProperties verification) {
        String base = verification.linkBaseUrl();
        if (!base.startsWith("https://") && !base.startsWith("http://localhost") && !base.startsWith("http://127.0.0.1")
                && !base.startsWith("http://10.0.2.2")) {
            log.warn("app.email-verification.link-base-url is not HTTPS: verification links would travel unencrypted");
        }
        if (!mail.configured()) {
            log.warn("E-mail delivery is not configured (MAIL_HOST / MAIL_FROM): verification e-mails are NOT sent, so "
                    + "new accounts cannot be confirmed until it is");
            return new NotConfiguredMailer();
        }
        log.info("Verification e-mails go through SMTP {}", mail);
        return new SmtpVerificationMailer(mail);
    }

    /**
     * One background thread sends the messages, so a request's answer time never tells whether an e-mail went out (and
     * a slow mail server never holds a request). Bounded: a full queue drops the message with a warning (the person can
     * ask again); it never blocks sign-up.
     */
    @Bean(name = "verificationMailExecutor", destroyMethod = "shutdown")
    public ExecutorService verificationMailExecutor() {
        return new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(200), runnable -> {
            Thread thread = new Thread(runnable, "verification-mail");
            thread.setDaemon(true);
            return thread;
        }, (runnable, executor) -> log.warn("Verification e-mail dropped: the send queue is full"));
    }

    /** Sends nothing. Never pretends a message went out. */
    static final class NotConfiguredMailer implements VerificationMailer {

        @Override
        public boolean sendVerification(String toEmail, String link, Duration lifetime) {
            log.warn("Verification e-mail not sent: e-mail delivery is not configured");
            return false;
        }

        @Override
        public boolean configured() {
            return false;
        }
    }
}
