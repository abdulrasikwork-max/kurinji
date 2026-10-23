package com.safesphere.backend.social.policy;

/**
 * Phase 13C (C2): what a post's author may learn about the butterflies on it. Only the author, never on an Only-me post,
 * and the number only from [FLOOR] people up: with a small audience an exact "1" would tell the author who it was, so 0, 1
 * and 2 all read "fewer than 3 so far". Pure.
 *
 * @param count          the number of people, or null below the floor
 * @param fewerThanThree true below the floor
 */
public record ButterflyTally(Integer count, boolean fewerThanThree) {

    public static final int FLOOR = 3;

    public static ButterflyTally of(long people) {
        return people >= FLOOR ? new ButterflyTally((int) Math.min(people, Integer.MAX_VALUE), false)
                : new ButterflyTally(null, true);
    }
}
