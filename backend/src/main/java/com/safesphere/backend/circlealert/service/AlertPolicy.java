package com.safesphere.backend.circlealert.service;

import com.safesphere.backend.circlealert.entity.CircleAlertEnums.Kind;
import com.safesphere.backend.circlealert.entity.CircleAlertEnums.SkipReason;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * N6 (D20, docs/PHASE_N6.md §3): the rules of the automatic Safety Circle texts, pure and unit-tested (AlertPolicyTest).
 * The dispatcher only asks this class; it never decides on its own.
 *
 * <ul>
 *   <li><b>Timing:</b> the first text goes at the first location or [sendDelay] (20 s) after the SOS reached the server,
 *       whichever comes first; an SOS that reached the server more than [staleAfter] (30 min) after it started is not
 *       texted; one that reached it more than [lateAfter] after it started is texted as "sent late".</li>
 *   <li><b>Who:</b> enabled contacts by priority; Indian mobile numbers only; the same number once; numbers on the stop
 *       list never; at most [maxContacts] (5) texted, every other enabled contact is shown as "text them yourself".</li>
 *   <li><b>What:</b> only allow-listed facts reach a text: the person's name (plain ASCII), their own number, the time,
 *       what happened, a map link, "sent late". Nothing typed by anyone else, so IRL cannot be used as an SMS gateway.
 *       One 160-character part (the name is shortened if it must be).</li>
 *   <li><b>Retries:</b> [maxAttempts] provider attempts, [retryDelay] times the attempt number apart, then FAILED.</li>
 * </ul>
 */
public final class AlertPolicy {

    /** One plain-ASCII SMS part (each part is billed). */
    public static final int ONE_PART = 160;
    static final String NO_NAME = "Your contact";
    static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH);
    private static final int MIN_NAME = 10;

    private AlertPolicy() {
    }

    // ---- timing ------------------------------------------------------------------------------------------------------

    /** The SOS reached the server too long after it started: no automatic text (the person texts by hand). */
    public static boolean tooLate(LocalDateTime startedAt, LocalDateTime receivedAt, Duration staleAfter) {
        return Duration.between(startedAt, receivedAt).compareTo(staleAfter) > 0;
    }

    /** Texted, but marked "sent late" (an SOS started offline and recorded later). */
    public static boolean late(LocalDateTime startedAt, LocalDateTime receivedAt, Duration lateAfter) {
        return Duration.between(startedAt, receivedAt).compareTo(lateAfter) > 0;
    }

    public static LocalDateTime firstDue(LocalDateTime receivedAt, Duration sendDelay) {
        return receivedAt.plus(sendDelay);
    }

    /** A queued row goes now: its time has come, or (first text only) the first location arrived. */
    public static boolean due(Kind kind, LocalDateTime nextAttemptAt, int attempts, boolean hasLocation,
                              LocalDateTime now) {
        if (!nextAttemptAt.isAfter(now)) return true;
        return kind == Kind.SOS_STARTED && attempts == 0 && hasLocation;
    }

    /** A follow-up with the location: once, only after a first text without one, only within the window. */
    public static boolean followUpDue(boolean firstHadLocation, LocalDateTime firstSentAt, boolean hasLocationNow,
                                      boolean stillActive, boolean followUpExists, Duration window, LocalDateTime now) {
        return !firstHadLocation && hasLocationNow && stillActive && !followUpExists
                && !firstSentAt.plus(window).isBefore(now);
    }

    /**
     * The closing text after "I'm safe" / "False alarm": only when a first text actually went out to that contact, only
     * once, and NEVER for an SOS ended from another phone (only the person on the phone can say they are OK).
     */
    public static boolean closingDue(boolean firstWentOut, boolean endedElsewhere, boolean closingExists) {
        return firstWentOut && !endedElsewhere && !closingExists;
    }

    /** When to try again after a refusal; null = no more attempts (FAILED). */
    public static LocalDateTime retryAt(int attemptsSoFar, int maxAttempts, Duration retryDelay, LocalDateTime now) {
        if (attemptsSoFar >= maxAttempts) return null;
        return now.plus(retryDelay.multipliedBy(Math.max(1, attemptsSoFar)));
    }

    // ---- who ---------------------------------------------------------------------------------------------------------

    /** A contact as the planner sees it: id, its number already normalised (null = not an Indian mobile), its key. */
    public record Recipient(long contactId, String numberE164, String numberKey) {
    }

    /** The plan for one contact: texted (reason null) or not (the reason). */
    public record Planned(long contactId, SkipReason reason) {
        public boolean texted() {
            return reason == null;
        }
    }

    /**
     * Who gets the first text. [inOrder] = the enabled contacts by priority (primary first). Not an Indian mobile, a
     * number seen before, a stopped number: not texted and not counted; after [maxContacts] texted: NOT_IN_FIRST_FIVE.
     */
    public static List<Planned> plan(List<Recipient> inOrder, Predicate<String> stopped, int maxContacts) {
        List<Planned> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int texted = 0;
        for (Recipient r : inOrder) {
            SkipReason reason;
            if (r.numberE164() == null) reason = SkipReason.NOT_INDIAN_MOBILE;
            else if (!seen.add(r.numberE164())) reason = SkipReason.DUPLICATE_NUMBER;
            else if (stopped.test(r.numberKey())) reason = SkipReason.STOPPED;
            else if (texted >= maxContacts) reason = SkipReason.NOT_IN_FIRST_FIVE;
            else {
                reason = null;
                texted++;
            }
            out.add(new Planned(r.contactId(), reason));
        }
        return out;
    }

    /** The limits, checked just before a text goes out (in this order). Null = within every limit. */
    public static SkipReason overLimit(boolean stopped, long toThisNumber, int perNumber, long serverWide, int serverMax) {
        if (stopped) return SkipReason.STOPPED;
        if (toThisNumber >= perNumber) return SkipReason.NUMBER_LIMIT;
        if (serverWide >= serverMax) return SkipReason.SERVER_LIMIT;
        return null;
    }

    // ---- what --------------------------------------------------------------------------------------------------------

    /** A map position (only ever the person's own SOS location). */
    public record Position(BigDecimal latitude, BigDecimal longitude) {
        @Override
        public String toString() {
            return "Position{<redacted>}";
        }
    }

    /** The ONLY facts a text may contain (the allow-list). */
    public record Facts(String name, String phoneE164, Instant at, Position position, boolean late) {
        @Override
        public String toString() {
            return "Facts{<redacted>}";
        }
    }

    public static String text(Kind kind, Facts facts) {
        String phone = national(facts.phoneE164());
        String time = time(facts.at());
        Function<String, String> build = switch (kind) {
            case SOS_STARTED -> name -> "IRL SOS" + (facts.late() ? " (sent late)" : "") + ": " + name
                    + " pressed SOS at " + time + ". "
                    + (facts.position() == null ? "Location not known yet." : "Location: " + mapLink(facts.position()))
                    + " Call " + phone + " now. No answer? Call 112.";
            case SOS_LOCATION -> name -> "IRL SOS update: " + name + " was here at " + time + ": "
                    + mapLink(facts.position()) + " Call " + phone + " now.";
            case SOS_RESOLVED -> name -> "IRL: " + name + " ended the SOS at " + time
                    + " and marked it I'm safe. Call " + phone + " if you are worried.";
            case SOS_CANCELLED -> name -> "IRL: " + name + " marked the SOS at " + time
                    + " as a false alarm. Call " + phone + " if you are worried.";
            case TEST -> name -> testText();
            default -> throw new IllegalArgumentException("No text for " + kind);
        };
        return fit(build, asciiName(facts.name()));
    }

    /** "Test my alerts": proves the chain end to end; says plainly that it is not an emergency. */
    public static String testText() {
        return "IRL test, not an emergency: your Safety Circle alerts work. In an SOS each contact gets your name,"
                + " the time, a map link and your number.";
    }

    /** What the person sees before choosing (the exact text, with their own name and number; the place shown as such). */
    public static String preview(String name, String phoneE164, Instant at) {
        String withoutPlace = text(Kind.SOS_STARTED, new Facts(name, phoneE164, at, null, false));
        return withoutPlace.replace("Location not known yet.", "Location: maps.google.com/?q=(where you are)");
    }

    /** Shortens the name (never anything else) until the text fits one part; a name never goes below 10 characters. */
    static String fit(Function<String, String> build, String name) {
        String text = build.apply(name);
        if (text.length() <= ONE_PART) return text;
        int keep = Math.max(MIN_NAME, name.length() - (text.length() - ONE_PART));
        return build.apply(name.substring(0, Math.min(keep, name.length())).strip());
    }

    /**
     * The name as plain ASCII (one Unicode character would cut an SMS part to 70 characters): accents are dropped
     * (é → e), the typographic apostrophe becomes ', anything else non-ASCII is left out. A name with fewer than two
     * letters left (a name written in Tamil, for example) becomes "Your contact": the text still carries the person's
     * number, so the contact knows who it is.
     */
    static String asciiName(String raw) {
        if (raw == null) return NO_NAME;
        String decomposed = Normalizer.normalize(raw.replace('’', '\''), Normalizer.Form.NFKD);
        StringBuilder out = new StringBuilder();
        for (char c : decomposed.toCharArray()) {
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || c == ' ' || c == '.' || c == '\'' || c == '-') {
                out.append(c);
            }
        }
        String clean = out.toString().replaceAll("\\s+", " ").strip();
        long letters = clean.chars().filter(Character::isLetter).count();
        return letters >= 2 ? clean : NO_NAME;
    }

    /** India time, "9:41 PM". */
    static String time(Instant at) {
        return CLOCK.format(at.atZone(INDIA));
    }

    /** A short map link (no https: phones link "maps.google.com/..." by themselves); 5 decimals = about 1 m. */
    static String mapLink(Position p) {
        return "maps.google.com/?q=" + p.latitude().setScale(5, RoundingMode.HALF_UP).toPlainString() + ","
                + p.longitude().setScale(5, RoundingMode.HALF_UP).toPlainString();
    }

    static String national(String e164) {
        return e164 != null && e164.startsWith("+91") && e164.length() == 13 ? e164.substring(3) : "";
    }
}
