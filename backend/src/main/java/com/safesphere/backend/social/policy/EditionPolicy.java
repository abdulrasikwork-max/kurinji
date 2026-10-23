package com.safesphere.backend.social.policy;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Phase 13C (C7, C8): the weekly Edition and its explainable order. Pure.
 *
 * The order uses only the post's content and the viewer's OWN choices: a place, a "when", a tip, the viewer's interests,
 * and whether someone has been here too (yes/no). Never butterflies, views, comment counts or any prediction of
 * engagement. Every item carries the reasons it was picked, so the phone can answer "Why am I seeing this?".
 */
public final class EditionPolicy {

    /** The Edition week follows the launch city's calendar (Chennai, D5); stored times stay UTC. */
    public static final ZoneId EDITION_ZONE = ZoneId.of("Asia/Kolkata");

    public static final int CITY_CAP = 12;
    public static final int ASKS_CAP = 5;
    public static final int THEME_CAP = 6;
    /** Asks from the last 4 weeks, theme posts from the last 8; city posts since the start of last week. */
    public static final Duration ASKS_WINDOW = Duration.ofDays(28);
    public static final Duration THEME_WINDOW = Duration.ofDays(56);
    /** C9: "From your connections" covers the last 30 days. */
    public static final Duration CONNECTIONS_WINDOW = Duration.ofDays(30);

    public enum Section { CITY, ASKS, THEME }

    public enum Reason {
        IN_THIS_CITY, RECENT, HAS_PLACE, HAS_WHEN, HAS_TIP, MATCHES_YOUR_INTERESTS, BEEN_HERE_TOO, OPEN_ASK,
        THEME_OF_THE_WEEK, FROM_A_CONNECTION
    }

    /**
     * What the order may know about one candidate post.
     *
     * @param hasPlace        EXACT or AREA (others learn at least the area)
     * @param hasWhen         a month and a time of day
     * @param hasTip          a "tip for the next person"
     * @param matchesInterest its category is one of the viewer's interests
     * @param beenHereToo     at least one visible "Been here too" by someone other than the author
     */
    public record Candidate<T>(T item, boolean hasPlace, boolean hasWhen, boolean hasTip, boolean matchesInterest,
                               boolean beenHereToo, LocalDateTime visibleFrom, long id) {
    }

    public record Picked<T>(T item, List<Reason> reasons) {
    }

    private EditionPolicy() {
    }

    /** The Monday (in the Edition zone) of the week holding [now]. */
    public static LocalDate weekOf(Instant now) {
        return LocalDate.ofInstant(now, EDITION_ZONE).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    /** Midnight at the start of [monday] in the Edition zone, as a UTC wall-clock time (how times are stored). */
    public static LocalDateTime startUtc(LocalDate monday) {
        return LocalDateTime.ofInstant(monday.atStartOfDay(EDITION_ZONE).toInstant(), ZoneOffset.UTC);
    }

    /** The category of the week: the closed list in order, one per week, the same for everyone. */
    public static ProfileLists.Interest theme(LocalDate monday) {
        ProfileLists.Interest[] all = ProfileLists.Interest.values();
        long week = Math.floorDiv(monday.toEpochDay(), 7);
        return all[(int) Math.floorMod(week, (long) all.length)];
    }

    public static int score(Candidate<?> c) {
        return (c.hasPlace() ? 1 : 0) + (c.hasWhen() ? 1 : 0) + (c.hasTip() ? 1 : 0) + (c.matchesInterest() ? 1 : 0)
                + (c.beenHereToo() ? 1 : 0);
    }

    /** Higher score first; ties newest first (then the higher id, so the order is total and repeatable). */
    public static <T> List<Picked<T>> pick(Section section, List<Candidate<T>> candidates, int cap) {
        List<Candidate<T>> sorted = new ArrayList<>(candidates);
        sorted.sort(Comparator.<Candidate<T>>comparingInt(EditionPolicy::score).reversed()
                .thenComparing(Candidate::visibleFrom, Comparator.reverseOrder())
                .thenComparing(Candidate::id, Comparator.reverseOrder()));
        List<Picked<T>> out = new ArrayList<>();
        for (Candidate<T> c : sorted) {
            if (out.size() >= cap) break;
            out.add(new Picked<>(c.item(), reasons(section, c)));
        }
        return out;
    }

    /** The reasons in a fixed order: why this section, then what the content has. */
    public static List<Reason> reasons(Section section, Candidate<?> c) {
        Set<Reason> set = EnumSet.noneOf(Reason.class);
        set.add(Reason.IN_THIS_CITY);
        switch (section) {
            case CITY -> set.add(Reason.RECENT);
            case ASKS -> set.add(Reason.OPEN_ASK);
            case THEME -> set.add(Reason.THEME_OF_THE_WEEK);
        }
        if (c.hasPlace()) set.add(Reason.HAS_PLACE);
        if (c.hasWhen()) set.add(Reason.HAS_WHEN);
        if (c.hasTip()) set.add(Reason.HAS_TIP);
        if (c.matchesInterest()) set.add(Reason.MATCHES_YOUR_INTERESTS);
        if (c.beenHereToo()) set.add(Reason.BEEN_HERE_TOO);
        return List.copyOf(set);
    }
}
