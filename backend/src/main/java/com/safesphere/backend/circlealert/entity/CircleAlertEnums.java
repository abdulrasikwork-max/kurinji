package com.safesphere.backend.circlealert.entity;

/** Value sets of "circle_alerts" (migrations V8 and V37 check the stored text). Kept together because they are small. */
public final class CircleAlertEnums {

    private CircleAlertEnums() {
    }

    /** What the Safety Circle contact was told. */
    public enum Kind {
        /** A Safe Walk check-in got no answer (level 2). Journey only. Old rows only: N6-B1 removed the walk report. */
        WALK_NO_RESPONSE,
        /** An SOS started (level 3). Emergency only. */
        SOS_STARTED,
        /** Follow-up with the first location, when the first SOS message had none. Emergency only. */
        SOS_LOCATION,
        /** The person answered after a WALK_NO_RESPONSE alert. Journey only. Old rows only (N6-B1). */
        ALL_CLEAR,
        /** The person marked the SOS as resolved ("I'm safe"). Emergency only. */
        SOS_RESOLVED,
        /** The person cancelled the SOS as a false alarm. Emergency only. */
        SOS_CANCELLED,
        /** N6: "Test my alerts", texted by the server to the person's OWN number. No emergency, no contact. */
        TEST;

        public boolean isForJourney() {
            return this == WALK_NO_RESPONSE || this == ALL_CLEAR;
        }
    }

    public enum Channel {
        /** Sent by the phone automatically (before N6: the person opted in and allowed SEND_SMS). */
        SMS_AUTO,
        /** The phone's SMS app was opened with the message; the person decides whether to send it. */
        SMS_COMPOSER,
        /** N6 (D20): texted by the server through the SMS provider (no phone permission involved). */
        SERVER_SMS;

        /** What a phone may report about itself. */
        public boolean isPhone() {
            return this != SERVER_SMS;
        }
    }

    /**
     * Honest delivery states. SENT means "handed to the mobile network / accepted by the provider", not "received".
     * [rank] only grows for what the phone reports: a late SENT report can never overwrite DELIVERED. QUEUED and SKIPPED
     * are the server queue's own states (N6) and are never accepted from a phone.
     */
    public enum Status {
        COMPOSER_OPENED(1),
        SENT(1),
        FAILED(1),
        DELIVERED(2),
        /** N6: waiting in the server's queue (for the first location, the 20 s, or a retry). */
        QUEUED(0),
        /** N6: the server will not text this one ([SkipReason] says why); the person can text them by hand. */
        SKIPPED(1);

        private final int rank;

        Status(int rank) {
            this.rank = rank;
        }

        public int rank() {
            return rank;
        }

        public boolean isPhone() {
            return this != QUEUED && this != SKIPPED;
        }
    }

    /** N6: why the server will not text a contact (V37 CHECK). Never says more to the person than "text them yourself". */
    public enum SkipReason {
        /** Fast2SMS delivers to Indian mobile numbers only. */
        NOT_INDIAN_MOBILE,
        /** Only the first five contacts (by priority) are texted. */
        NOT_IN_FIRST_FIVE,
        /** Two contacts with the same number: texted once. */
        DUPLICATE_NUMBER,
        /** The number asked IRL for no alerts (the admin stop list). Shown to the person only as "IRL can't text". */
        STOPPED,
        /** That number already got the daily maximum of IRL alerts. */
        NUMBER_LIMIT,
        /** The person's SOS alert rounds for the day are used up. */
        PERSON_LIMIT,
        /** The server's daily maximum of alert texts is reached. */
        SERVER_LIMIT,
        /** The SOS reached the server more than 30 minutes after it started. */
        TOO_LATE,
        /** The SOS ended before this text went out (a false alarm in the first 20 s sends nothing). */
        ENDED,
        /** The contact was deleted or switched off before the text went out. */
        CONTACT_GONE
    }
}
