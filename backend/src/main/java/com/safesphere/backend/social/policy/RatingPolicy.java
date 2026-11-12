package com.safesphere.backend.social.policy;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Phase 17G-2 (U5): the one rule for a place's public rating. Pure. A place shows ONE average, as text with one decimal, and only
 * when at least {@link #MIN_RATERS} people who marked "I went" (past probation) have rated it. The average is Bayesian: it starts
 * from a prior of {@link #PRIOR_MEAN} worth {@link #PRIOR_WEIGHT} ratings, so a handful of extreme ratings cannot swing it:
 * {@code (PRIOR_MEAN * PRIOR_WEIGHT + sum) / (PRIOR_WEIGHT + raters)}.
 *
 * <p>What this deliberately does NOT offer: no count (not even "from 12 people"), no distribution, no times, no names, no
 * ordering. {@link #display} returns the text and nothing else; nothing may sort or rank by it (RatingIsolationTest).
 */
public final class RatingPolicy {

    /** No rating is shown before this many counted ratings. */
    public static final int MIN_RATERS = 5;
    public static final BigDecimal PRIOR_MEAN = new BigDecimal("3.5");
    public static final int PRIOR_WEIGHT = 5;
    public static final int MIN = 1;
    public static final int MAX = 5;

    private RatingPolicy() {
    }

    /** A rating a person may give: 1 to 5. */
    public static boolean isValid(Integer value) {
        return value != null && value >= MIN && value <= MAX;
    }

    /**
     * The public text ("4.3") for [raters] counted ratings adding up to [sum], or null while fewer than MIN_RATERS (or on
     * impossible input). Rounded half up to one decimal.
     */
    public static String display(long raters, long sum) {
        if (raters < MIN_RATERS || sum < raters * MIN || sum > raters * MAX) return null;
        BigDecimal top = PRIOR_MEAN.multiply(BigDecimal.valueOf(PRIOR_WEIGHT)).add(BigDecimal.valueOf(sum));
        BigDecimal average = top.divide(BigDecimal.valueOf(PRIOR_WEIGHT + raters), 1, RoundingMode.HALF_UP);
        return average.toPlainString();
    }

    // ---- Phase 18A-1 (2026-11-12): independent raters and weights -------------------------------------------------------------

    /** An account younger than this counts half (it is past probation, but it has little history). */
    public static final int ESTABLISHED_AFTER_DAYS = 30;
    /** A person who has rated fewer different places than this counts half (an account made for one place). */
    public static final int BROAD_FROM_PLACES = 3;
    private static final BigDecimal FULL = BigDecimal.ONE;
    private static final BigDecimal HALF = new BigDecimal("0.5");

    /** One counted rating with the two facts that decide its weight. Server-side only: never part of an answer. */
    public record Rater(long userId, int rating, boolean established, boolean broad) {
    }

    /** Two raters who are connected to each other (an accepted connection). */
    public record Link(long a, long b) {
    }

    /** Full weight for an established account that rates different places; half otherwise. Never zero: nobody is silenced. */
    static BigDecimal weight(Rater rater) {
        return rater.established() && rater.broad() ? FULL : HALF;
    }

    /**
     * The public text for the counted ratings of ONE place, or null. Two rules on top of the Bayesian average:
     * <ul>
     *   <li><b>Independent raters.</b> Raters connected to each other (directly or through other raters of this place) are ONE
     *       group and speak with ONE voice: the weighted mean of their ratings, with the weight of their strongest member.
     *       Nothing is shown before {@link #MIN_RATERS} groups. Five friends rating a friend's place are one voice.</li>
     *   <li><b>Weights.</b> See {@link #weight}.</li>
     * </ul>
     * With unconnected, established, broad raters this equals {@link #display(long, long)}.
     */
    public static String display(java.util.List<Rater> raters, java.util.Collection<Link> links) {
        if (raters == null || raters.isEmpty()) return null;
        java.util.Map<Long, Long> parent = new java.util.HashMap<>();
        for (Rater rater : raters) {
            if (!isValid(rater.rating()) || parent.put(rater.userId(), rater.userId()) != null) return null; // impossible input
        }
        if (links != null) {
            for (Link link : links) {
                if (!parent.containsKey(link.a()) || !parent.containsKey(link.b())) continue;
                parent.put(root(parent, link.a()), root(parent, link.b()));
            }
        }
        java.util.Map<Long, BigDecimal[]> groups = new java.util.HashMap<>(); // root -> {sum of w*r, sum of w, strongest w}
        for (Rater rater : raters) {
            BigDecimal w = weight(rater);
            BigDecimal[] g = groups.computeIfAbsent(root(parent, rater.userId()),
                    k -> new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO});
            g[0] = g[0].add(w.multiply(BigDecimal.valueOf(rater.rating())));
            g[1] = g[1].add(w);
            g[2] = g[2].max(w);
        }
        if (groups.size() < MIN_RATERS) return null;
        BigDecimal top = PRIOR_MEAN.multiply(BigDecimal.valueOf(PRIOR_WEIGHT));
        BigDecimal bottom = BigDecimal.valueOf(PRIOR_WEIGHT);
        for (BigDecimal[] g : groups.values()) {
            BigDecimal voice = g[0].divide(g[1], 6, RoundingMode.HALF_UP);
            top = top.add(g[2].multiply(voice));
            bottom = bottom.add(g[2]);
        }
        return top.divide(bottom, 1, RoundingMode.HALF_UP).toPlainString();
    }

    private static long root(java.util.Map<Long, Long> parent, long id) {
        long at = id;
        while (parent.get(at) != at) at = parent.get(at);
        parent.put(id, at);
        return at;
    }
}
