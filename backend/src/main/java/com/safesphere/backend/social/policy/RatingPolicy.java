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
}
