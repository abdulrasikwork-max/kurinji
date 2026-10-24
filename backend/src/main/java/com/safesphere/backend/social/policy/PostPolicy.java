package com.safesphere.backend.social.policy;

import com.safesphere.backend.social.entity.PostEnums.Audience;
import com.safesphere.backend.social.entity.PostEnums.City;
import com.safesphere.backend.social.entity.PostEnums.PostType;
import com.safesphere.backend.social.entity.PostEnums.Precision;
import java.text.Normalizer;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Phase 13B: the shape of a post (design §3.2, §4.2, §4.3). Pure; the service turns a refusal into a 400 with this
 * text. The phone mirrors these rules (ComposerPolicy) so most mistakes never reach the server.
 *
 *   Experience: body 1-5000 characters, optional tip (1-300) for the next person, the month it happened (required,
 *               never in the future), optional time of day and category.
 *   Ask:        body 10-1000 characters (a real question), no tip, no month; optional time of day and category.
 *   Where:      EXACT and AREA need a place; CITY needs a city and no place; NONE has neither.
 *   Photos:     at most 6.
 */
public final class PostPolicy {

    public static final int EXPERIENCE_BODY_MAX = 5000;
    public static final int ASK_BODY_MIN = 10;
    public static final int ASK_BODY_MAX = 1000;
    public static final int TIP_MAX = 300;
    public static final int MAX_PHOTOS = 6;

    /** A post as the person asked for it, after parsing and cleaning. */
    public record Draft(PostType type, String body, String tip, Precision precision, boolean hasPlace, City city,
                        YearMonth month, Audience audience, int photos) {
    }

    private PostPolicy() {
    }

    /** Empty when the draft is acceptable; otherwise the reason, in words the person can act on. */
    public static Optional<String> check(Draft d, LocalDateTime nowUtc) {
        if (d.type() == null) return Optional.of("Choose Experience or Ask");
        if (d.precision() == null) return Optional.of("Choose how much of the place to show");
        if (d.audience() == null) return Optional.of("Choose who can see this");
        int length = length(d.body());
        if (d.type() == PostType.EXPERIENCE) {
            if (length < 1) return Optional.of("Write what happened");
            if (length > EXPERIENCE_BODY_MAX) return Optional.of("An experience has at most 5000 characters");
            if (d.tip() != null && length(d.tip()) > TIP_MAX) return Optional.of("A tip has at most 300 characters");
            if (d.month() == null) return Optional.of("Choose the month it happened");
            if (!TimeBands.isAllowedMonth(d.month(), nowUtc)) return Optional.of("Choose a month that is not in the future");
        } else {
            if (length < ASK_BODY_MIN) return Optional.of("Write your question in at least 10 characters");
            if (length > ASK_BODY_MAX) return Optional.of("A question has at most 1000 characters");
            if (d.tip() != null) return Optional.of("A question has no tip");
            if (d.month() != null) return Optional.of("A question has no month");
        }
        switch (d.precision()) {
            case EXACT, AREA -> {
                if (!d.hasPlace()) return Optional.of("Choose a place");
                if (d.city() != null) return Optional.of("Choose a place or a city, not both");
            }
            case CITY -> {
                if (d.city() == null) return Optional.of("Choose a city");
                if (d.hasPlace()) return Optional.of("Choose a place or a city, not both");
            }
            case NONE -> {
                if (d.hasPlace() || d.city() != null) return Optional.of("No place was chosen, so send none");
            }
        }
        if (d.photos() > MAX_PHOTOS) return Optional.of("Add at most 6 photos");
        return Optional.empty();
    }

    /** Edits may keep or NARROW who can see a post; widening needs a new post (design §4.1: "widening asks again"). */
    public static boolean isNarrowerOrSame(Audience before, Audience after) {
        return after.ordinal() >= before.ordinal();
    }

    /** Edits may keep or NARROW how much of the place is shown. */
    public static boolean isNarrowerOrSame(Precision before, Precision after) {
        return after.ordinal() >= before.ordinal();
    }

    /**
     * Cleans a post body: NFC, Windows line ends made "\n", control and invisible formatting characters removed (line
     * breaks kept), spaces at line ends removed, at most one empty line in a row, trimmed. Null stays null.
     */
    public static String cleanBody(String raw) {
        if (raw == null) return null;
        String text = Normalizer.normalize(raw.replace("\r\n", "\n").replace('\r', '\n'), Normalizer.Form.NFC);
        StringBuilder out = new StringBuilder(text.length());
        text.codePoints().forEach(cp -> {
            int type = Character.getType(cp);
            if (cp == '\n') {
                out.append('\n');
            } else if (Character.isWhitespace(cp) || Character.isSpaceChar(cp)) {
                out.append(' ');
            } else if (type != Character.CONTROL && type != Character.FORMAT && type != Character.UNASSIGNED
                    && type != Character.PRIVATE_USE && type != Character.SURROGATE) {
                out.appendCodePoint(cp);
            }
        });
        return out.toString().replaceAll(" {2,}", " ").replaceAll(" *\n *", "\n").replaceAll("\n{3,}", "\n\n").trim();
    }

    /** Single-line text (tip, comment): the profile cleaning. Empty becomes null. */
    public static String cleanLine(String raw) {
        String cleaned = ProfileTextPolicy.clean(raw);
        return cleaned == null || cleaned.isEmpty() ? null : cleaned;
    }

    public static int length(String text) {
        return text == null ? 0 : text.codePointCount(0, text.length());
    }

    /** Phase 13D (M16): accounts on probation may not share links (the profile's link rule, applied to their texts). */
    public static final String NO_LINKS_ON_PROBATION =
            "New accounts can share links after their first three days. Please remove the link.";

    /** Phase 13D: whether any of [texts] contains a link (the same pattern as names and profiles). */
    public static boolean containsLink(String... texts) {
        for (String text : texts) {
            if (text != null && LINK.matcher(text).find()) return true;
        }
        return false;
    }

    private static final Pattern LINK = Pattern.compile(
            "(?i)(https?://|www\\.|\\b[a-z0-9-]+\\.(com|in|net|org|io|me|app|co|xyz|link|ly|info|biz|site|online|top)\\b)");
}
