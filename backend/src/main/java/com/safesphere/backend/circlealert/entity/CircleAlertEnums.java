package com.safesphere.backend.circlealert.entity;

/** Value sets of "circle_alerts" (migration V8 checks the stored text). Kept together because they are small. */
public final class CircleAlertEnums {

    private CircleAlertEnums() {
    }

    /** What the Safety Circle contact was told. */
    public enum Kind {
        /** A Safe Walk check-in got no answer (level 2). Journey only. */
        WALK_NO_RESPONSE,
        /** An SOS started (level 3). Emergency only. */
        SOS_STARTED,
        /** Follow-up with the first location, when the first SOS message had none. Emergency only. */
        SOS_LOCATION,
        /** The person answered after a WALK_NO_RESPONSE alert. Journey only. */
        ALL_CLEAR,
        /** The person marked the SOS as resolved ("I'm safe"). Emergency only. */
        SOS_RESOLVED,
        /** The person cancelled the SOS as a false alarm. Emergency only. */
        SOS_CANCELLED;

        public boolean isForJourney() {
            return this == WALK_NO_RESPONSE || this == ALL_CLEAR;
        }
    }

    public enum Channel {
        /** Sent by the phone automatically (the person opted in and allowed SEND_SMS). */
        SMS_AUTO,
        /** The phone's SMS app was opened with the message; the person decides whether to send it. */
        SMS_COMPOSER
    }

    /**
     * Honest delivery states. SENT means "handed to the mobile network", not "received". [rank] only grows:
     * a late SENT report can never overwrite DELIVERED.
     */
    public enum Status {
        COMPOSER_OPENED(1),
        SENT(1),
        FAILED(1),
        DELIVERED(2);

        private final int rank;

        Status(int rank) {
            this.rank = rank;
        }

        public int rank() {
            return rank;
        }
    }
}
