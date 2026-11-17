package com.safesphere.backend.social.policy;

import com.safesphere.backend.social.entity.MomentEnums.Audience;
import com.safesphere.backend.social.entity.ContentModeration.ModerationState;
import java.util.EnumSet;
import java.util.Set;

/**
 * Phase 13A: THE one rule for who can see what in the social features (EXPERIENCE_PLATFORM_DESIGN.md §11.2). Every
 * read path asks this class; nothing decides visibility on its own. Pure: the caller supplies the facts.
 *
 * Profiles (13A, N3) and moments (N4). A moderation state (13D) is part of the rule: HELD and REMOVED content is seen
 * by its author only (always, marked, so nothing is ever hidden from them silently); COLLAPSED content stays visible
 * behind a tap.
 */
public final class Visibility {

    /**
     * What is known about a viewer and the owner of something. Phase N3: [sameCommunity] = the owner is an active member
     * of the viewer's community (the viewer's own membership is checked before any read: SocialAccess.requireMember).
     */
    public record Facts(boolean self, boolean blockedEitherWay, boolean ownerActive, boolean sameCommunity) {
    }

    /**
     * Phase N4: the facts about a viewer and one moment. [people]: as for a profile, where sameCommunity also means the
     * moment belongs to the viewer's community. [connected] = a mutual connection with the author. [started] = others
     * could see it by now (visible_at reached); [live] = started and inside its 24 hours; [kept] = the author keeps it
     * on their profile; [moderation] = its moderation state.
     */
    public record MomentFacts(Facts people, boolean connected, Audience audience,
                              boolean started, boolean live, boolean kept, ModerationState moderation) {
    }

    private Visibility() {
    }

    /**
     * Phase N4: a moment is visible to its author always (marked when it waits, is held or was removed: no shadow
     * states). Anyone else needs all of this: they can see the author's profile (active account, an active member of
     * the same community, no block either way); the moment has started; no moderator or rule holds or removed it; it is
     * live, or the author keeps it (N-48: a kept moment stays readable on the profile after its 24 hours, an un-kept one
     * is gone); and the audience includes them: COMMUNITY, or MY_PEOPLE while connected (N-46).
     */
    public static boolean canSeeMoment(MomentFacts facts) {
        if (facts.people().self()) return true;
        if (!canSeeProfile(facts.people()) || !facts.started()) return false;
        if (!facts.moderation().visibleToOthers()) return false;
        if (!facts.live() && !facts.kept()) return false;
        return switch (facts.audience()) {
            case COMMUNITY -> true;
            case MY_PEOPLE -> facts.connected();
        };
    }

    /**
     * Phase N4: whether a moment appears on Today (the wall). Only a LIVE moment that others may see: Today ends, so an
     * ended moment never comes back, kept or not (N-48), and the author's own wall shows exactly what everyone else
     * gets (their waiting, held or removed moments are listed for them under You instead). A mute hides the author's
     * moments from Today only; a moment opened deliberately asks [canSeeMoment].
     */
    public static boolean showOnToday(MomentFacts facts, boolean authorMuted) {
        if (!facts.started() || !facts.live() || !facts.moderation().visibleToOthers()) return false;
        return canSeeMoment(facts) && (facts.people().self() || !authorMuted);
    }

    /**
     * A profile is visible to its owner; to anyone else only while the owner's account is active (not disabled, not
     * erased), the owner is an active member of the viewer's community (Phase N3: nobody outside it, nobody not verified,
     * graduated or banned) and neither has blocked the other. Signed-out people never get here (every social endpoint
     * needs a JWT).
     */
    public static boolean canSeeProfile(Facts facts) {
        if (facts.self()) return true;
        return facts.ownerActive() && facts.sameCommunity() && !facts.blockedEitherWay();
    }

    /**
     * Phase N4: the moderation states a moment on Today can have (the same rule as [showOnToday], asked for each state,
     * so the list query can leave the others out in the database without a second definition).
     */
    public static Set<ModerationState> todayStates() {
        Facts self = new Facts(true, false, true, true);
        Set<ModerationState> states = EnumSet.noneOf(ModerationState.class);
        for (ModerationState state : ModerationState.values()) {
            if (showOnToday(new MomentFacts(self, false,
                    Audience.COMMUNITY, true, true, false, state),
                    false)) {
                states.add(state);
            }
        }
        return states;
    }

}
