package com.safesphere.backend.social.policy;

import com.safesphere.backend.social.entity.MomentEnums.Audience;
import com.safesphere.backend.social.entity.MomentEnums.Kind;
import java.text.Normalizer;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Phase N4: the rules of a moment, as the owner locked them on 2026-11-16 (docs/PHASE_N4.md §2, §3, §5). Pure; the
 * service turns a refusal into a 400 with this text. The numbers here are decisions, not settings: changing one is a
 * new decision, so none of them is read from configuration.
 *
 *   Moment        SAY or WHOS_IN; text up to 500 characters; at most one photo; the text is optional only next to a
 *                 photo (N-45). No editing (N-54).
 *   Audience      COMMUNITY or MY_PEOPLE, always chosen by the author (N-46).
 *   Who's in?     an optional time within the next 24 hours and an optional "where" of at most 60 characters of plain
 *                 text (N-50). A Say has neither.
 *   Life          24 hours from when others can first see it; data purged 7 days after that end (N-47).
 *   Keep          at most 6 kept moments, chosen while live (N-48).
 *   New members   7 days from the exact moment the membership was approved; established at exactly that moment + 7
 *                 days, computed on every request, nothing stored (N-56).
 */
public final class MomentPolicy {

    public static final int TEXT_MAX = 500;
    public static final int WHERE_MAX = 60;
    public static final int MAX_KEPT = 6;

    /** N-47: how long others can see a moment, counted from when they first could. */
    public static final Duration LIFE = Duration.ofHours(24);

    /** N-47: how long an ended moment's data may remain before it is purged. */
    public static final Duration PURGE_AFTER = Duration.ofDays(7);

    /** N-50: a Who's in? is for the next 24 hours. */
    public static final Duration PLAN_WINDOW = Duration.ofHours(24);

    /** N-56: the new-member period, counted from memberships.verified_at. */
    public static final Duration NEW_MEMBER_PERIOD = Duration.ofDays(7);

    public static final String NO_LINKS_FOR_NEW_MEMBERS =
            "New members can share links after their first week. Please remove the link.";

    /** A moment as the person asked for it, after parsing and cleaning. [photo] = a photo is attached. */
    public record Draft(Kind kind, Audience audience, String text, boolean photo, LocalDateTime planAt,
                        String planWhere) {
    }

    private MomentPolicy() {
    }

    /** Empty when the draft is acceptable at [nowUtc]; otherwise the reason, in words the person can act on. */
    public static Optional<String> check(Draft d, LocalDateTime nowUtc) {
        if (d.kind() == null) return Optional.of("Choose Say or Who's in?");
        if (d.audience() == null) return Optional.of("Choose who can see this");
        int length = length(d.text());
        if (length == 0 && !d.photo()) return Optional.of("Write something or add a photo");
        if (length > TEXT_MAX) return Optional.of("A moment has at most 500 characters");
        if (d.kind() != Kind.WHOS_IN && (d.planAt() != null || d.planWhere() != null)) {
            return Optional.of("Only a Who's in? has a time and a place");
        }
        if (d.planAt() != null && (!d.planAt().isAfter(nowUtc) || d.planAt().isAfter(nowUtc.plus(PLAN_WINDOW)))) {
            return Optional.of("Choose a time within the next 24 hours");
        }
        if (d.planWhere() != null && length(d.planWhere()) > WHERE_MAX) {
            return Optional.of("Where has at most 60 characters");
        }
        return Optional.empty();
    }

    /**
     * N-56: whether a member approved at [verifiedAt] is still a new member at [now]. Established at EXACTLY
     * verifiedAt + 7 days: one microsecond before is new, that instant and everything after is established. Computed
     * from the membership every time, so a new verification starts a new period and nothing else can extend or
     * shorten it (not a pause, not a role, not a move to a new number).
     */
    public static boolean isNewMember(LocalDateTime verifiedAt, LocalDateTime now) {
        return now.isBefore(establishedAt(verifiedAt));
    }

    /** N-56: the instant a member approved at [verifiedAt] becomes established. */
    public static LocalDateTime establishedAt(LocalDateTime verifiedAt) {
        return verifiedAt.plus(NEW_MEMBER_PERIOD);
    }

    /** N-47: an ended, un-kept moment may be purged once its end is MORE than 7 days in the past. */
    public static boolean purgeDue(LocalDateTime expiresAt, LocalDateTime now) {
        return expiresAt != null && expiresAt.plus(PURGE_AFTER).isBefore(now);
    }

    /**
     * Cleans a moment's text: NFC, Windows line ends made "\n", control and invisible formatting characters removed
     * (line breaks kept), other white space made a plain space, spaces at line ends removed, at most one empty line in a
     * row, trimmed. Empty becomes null.
     */
    public static String cleanText(String raw) {
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
        String cleaned = out.toString().replaceAll(" {2,}", " ").replaceAll(" *\n *", "\n")
                .replaceAll("\n{3,}", "\n\n").trim();
        return cleaned.isEmpty() ? null : cleaned;
    }

    /** Cleans one line of plain text (the "where", a question): no line breaks, white space collapsed. Empty = null. */
    public static String cleanLine(String raw) {
        String cleaned = ProfileTextPolicy.clean(raw);
        return cleaned == null || cleaned.isEmpty() ? null : cleaned;
    }

    /** Length in characters as a person counts them (code points), the unit of every limit here and in V35. */
    public static int length(String text) {
        return text == null ? 0 : text.codePointCount(0, text.length());
    }

    /** Whether any of [texts] contains a link (the same pattern as names, profiles and the 13D probation rule). */
    public static boolean containsLink(String... texts) {
        for (String text : texts) {
            if (text != null && LINK.matcher(text).find()) return true;
        }
        return false;
    }

    private static final Pattern LINK = Pattern.compile(
            "(?i)(https?://|www\\.|\\b[a-z0-9-]+\\.(com|in|net|org|io|me|app|co|xyz|link|ly|info|biz|site|online|top)\\b)");
}
