package com.safesphere.backend.auth.mail;

import java.time.Duration;

/** V25: the words of the verification e-mail (plain text: no tracking pixel, no remote images, no HTML to spoof). */
public final class VerificationEmailText {

    public static final String SUBJECT = "Confirm your email for Kurinji";

    private VerificationEmailText() {
    }

    public static String body(String link, Duration lifetime) {
        long minutes = Math.max(1, lifetime.toMinutes());
        return "Hello,\n\n"
                + "Someone created a Kurinji account with this email address. If it was you, open this link to confirm "
                + "that the address is yours:\n\n"
                + link + "\n\n"
                + "The link works once and for " + minutes + " minutes. If it has expired, open Kurinji and ask for a "
                + "new email.\n\n"
                + "If you did not create a Kurinji account, you can ignore this email: without the confirmation the "
                + "account cannot be used, and it is removed after a few days.\n\n"
                + "Kurinji will never ask you for your password by email.\n";
    }
}
