package com.safesphere.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The app.mail.* settings (V25): the SMTP server that sends verification e-mails. All values come from the
 * environment or the git-ignored backend/.env (MAIL_HOST, MAIL_PORT, MAIL_USERNAME, MAIL_PASSWORD, MAIL_FROM,
 * MAIL_STARTTLS). Without a host AND a from address nothing is sent (the start-up log says so) and accounts stay
 * unverified: e-mail being unavailable never verifies anyone.
 *
 * @param host     SMTP host; empty = e-mail delivery is not configured
 * @param port     SMTP port (587 = submission with STARTTLS)
 * @param username SMTP user (empty = no SMTP authentication)
 * @param password SMTP password or provider API key; never logged
 * @param from     the From address, e.g. "Kurinji <no-reply@example.org>"
 * @param starttls require STARTTLS (true by default; only a local test sink should turn it off)
 */
@ConfigurationProperties(prefix = "app.mail")
public record MailProperties(String host, Integer port, String username, String password, String from,
                             Boolean starttls) {

    public MailProperties {
        host = host == null ? "" : host.trim();
        if (port == null) port = 587;
        username = username == null ? "" : username.trim();
        password = password == null ? "" : password;
        from = from == null ? "" : from.trim();
        if (starttls == null) starttls = true;
        if (port < 1 || port > 65535) throw new IllegalStateException("app.mail.port is invalid");
    }

    public boolean configured() {
        return !host.isEmpty() && !from.isEmpty();
    }

    @Override
    public String toString() {
        return "MailProperties[host=" + host + ", port=" + port + ", auth=" + !username.isEmpty() + ", starttls="
                + starttls + "]";
    }
}
