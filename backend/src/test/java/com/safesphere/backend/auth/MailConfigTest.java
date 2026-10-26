package com.safesphere.backend.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.safesphere.backend.auth.mail.MailConfig;
import com.safesphere.backend.auth.mail.SmtpVerificationMailer;
import com.safesphere.backend.auth.mail.VerificationEmailText;
import com.safesphere.backend.auth.mail.VerificationMailer;
import com.safesphere.backend.config.EmailVerificationProperties;
import com.safesphere.backend.config.MailProperties;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** V25: the mailer choice is fail-safe, secrets stay out of code and logs, and the e-mail text is honest. */
class MailConfigTest {

    private static EmailVerificationProperties verification() {
        return new EmailVerificationProperties("https://kurinji.example/", null, null, null, null, null, null, null, null);
    }

    @Test
    void withoutAHostOrFromAddress_nothingIsSent_andNothingPretendsItWas() {
        VerificationMailer none = new MailConfig().verificationMailer(
                new MailProperties("", null, null, null, "Kurinji <no-reply@kurinji.example>", null), verification());
        assertThat(none.configured()).isFalse();
        assertThat(none.sendVerification("a@example.com", "https://kurinji.example/verify-email#t=x",
                Duration.ofMinutes(60))).isFalse();
        VerificationMailer noFrom = new MailConfig().verificationMailer(
                new MailProperties("smtp.example.org", 587, "user", "secret", "", true), verification());
        assertThat(noFrom.configured()).isFalse();
    }

    @Test
    void withHostAndFrom_itIsSmtp_andAnUnreachableServerIsAFailureNotAnException() {
        VerificationMailer smtp = new MailConfig().verificationMailer(
                new MailProperties("127.0.0.1", 1, "", "", "Kurinji <no-reply@kurinji.example>", false), verification());
        assertThat(smtp).isInstanceOf(SmtpVerificationMailer.class);
        assertThat(smtp.sendVerification("a@example.com", "https://kurinji.example/verify-email#t=x",
                Duration.ofMinutes(60))).isFalse();
    }

    @Test
    void mailPropertiesNeverPrintThePassword() {
        MailProperties properties = new MailProperties("smtp.example.org", 587, "apikey", "s3cr3t-value", "x@y.z", true);
        assertThat(properties.toString()).doesNotContain("s3cr3t-value").doesNotContain("apikey");
    }

    @Test
    void theLinkBaseIsNormalised_andMustBeHttp() {
        assertThat(verification().linkBaseUrl()).isEqualTo("https://kurinji.example");
        assertThatThrownBy(() -> new EmailVerificationProperties("javascript:alert(1)", null, null, null, null, null,
                null, null, null)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void theEmailSaysWhatItIs_andNeverAsksForThePassword() {
        String body = VerificationEmailText.body("https://kurinji.example/verify-email#t=T", Duration.ofMinutes(60));
        assertThat(body).contains("https://kurinji.example/verify-email#t=T").contains("60 minutes")
                .contains("If you did not create a Kurinji account").contains("never ask you for your password");
    }

    @Test
    void noMailCredentialIsHardCoded() throws IOException {
        String properties = Files.readString(Path.of("src/main/resources/application.properties"), StandardCharsets.UTF_8);
        assertThat(properties).contains("app.mail.password=${MAIL_PASSWORD:}").contains("app.mail.username=${MAIL_USERNAME:}");
    }
}
