package com.safesphere.backend.social.policy;

import com.safesphere.backend.social.entity.PostEnums.Audience;
import com.safesphere.backend.social.entity.PostEnums.CommentPolicy;
import com.safesphere.backend.social.entity.PostEnums.ModerationState;
import java.util.EnumSet;
import java.util.Set;

/**
 * Phase 13A: THE one rule for who can see what in the social features (EXPERIENCE_PLATFORM_DESIGN.md §11.2). Every
 * read path asks this class; nothing decides visibility on its own. Pure: the caller supplies the facts.
 *
 * 13A covers profiles. 13B extends it with posts (Everyone / Connections / Only me), their photos and comments, on the
 * same facts plus "connected" and "the delay is over" (Q1). 13D adds the moderation state: HELD and REMOVED content is
 * seen by its author only (always, marked, so nothing is ever hidden from them silently); COLLAPSED content stays
 * visible behind a tap. Perspectives are decided here too (canSeeComment).
 */
public final class Visibility {

    /**
     * What is known about a viewer and the owner of something. Phase N3: [sameCommunity] = the owner is an active member
     * of the viewer's community (the viewer's own membership is checked before any read: SocialAccess.requireMember).
     */
    public record Facts(boolean self, boolean blockedEitherWay, boolean ownerActive, boolean sameCommunity) {
    }

    /**
     * Phase 13B: the facts about a viewer and one post. [visibleFromReached] = the post's visible_from has passed;
     * [moderation] (13D) = its moderation state.
     */
    public record PostFacts(Facts people, boolean connected, Audience audience, boolean visibleFromReached,
                            ModerationState moderation) {

        /** The facts of a post nobody has moderated (13B/13C callers and tests). */
        public PostFacts(Facts people, boolean connected, Audience audience, boolean visibleFromReached) {
            this(people, connected, audience, visibleFromReached, ModerationState.NONE);
        }
    }

    /**
     * Phase 13D: the facts about a viewer and one perspective on a post the viewer can see. [writer] = facts about the
     * viewer and the perspective's writer; [postAuthor] = the viewer wrote the post; [hiddenByAuthor] (Q5),
     * [awaitingApproval] (Q6) and [moderation] are the perspective's own states.
     */
    public record CommentFacts(Facts writer, boolean postAuthor, boolean hiddenByAuthor, boolean awaitingApproval,
                               ModerationState moderation) {
    }

    /**
     * Phase N4: the facts about a viewer and one moment. [people]: as for a profile, where sameCommunity also means the
     * moment belongs to the viewer's community. [connected] = a mutual connection with the author. [started] = others
     * could see it by now (visible_at reached); [live] = started and inside its 24 hours; [kept] = the author keeps it
     * on their profile; [moderation] = its moderation state.
     */
    public record MomentFacts(Facts people, boolean connected, com.safesphere.backend.social.entity.MomentEnums.Audience audience,
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
     * A post is visible to its author always. Anyone else needs to be able to see the author's profile (active, no block
     * either way), the delay to be over, a moderation state others may see (not held, not removed), and the audience to
     * include them: EVERYONE, or CONNECTIONS while connected. ONLY_ME is never visible to anyone else. A photo or a
     * comment is visible only where its post is.
     */
    public static boolean canSeePost(PostFacts facts) {
        if (facts.people().self()) return true;
        if (!canSeeProfile(facts.people()) || !facts.visibleFromReached()) return false;
        if (!facts.moderation().visibleToOthers()) return false;
        return switch (facts.audience()) {
            case EVERYONE -> true;
            case CONNECTIONS -> facts.connected();
            case ONLY_ME -> false;
        };
    }

    /**
     * Phase 13C (13B Q8, C13): whether a post appears in a LIST of other people's posts (Edition, From your connections,
     * place and category pages): it must be visible, and its author must not be muted by the viewer. A mute never hides
     * a post opened deliberately (a profile visit, a link, the viewer's own Saved list); those ask [canSeePost].
     */
    public static boolean showInLists(PostFacts facts, boolean authorMuted) {
        return canSeePost(facts) && (facts.people().self() || !authorMuted);
    }

    /**
     * Phase 13D (M7): the weekly Edition is a selection, so it also leaves out collapsed posts (they stay on place,
     * category and profile pages and in From your connections, shown collapsed).
     */
    public static boolean showInEdition(PostFacts facts, boolean authorMuted) {
        return showInLists(facts, authorMuted) && facts.moderation() == ModerationState.NONE;
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
                    com.safesphere.backend.social.entity.MomentEnums.Audience.COMMUNITY, true, true, false, state),
                    false)) {
                states.add(state);
            }
        }
        return states;
    }

    /**
     * Who may add a Perspective: someone who can see the post, on a post that is not ONLY_ME and that no moderator has
     * held or removed, as the author's comment policy allows (EVERYONE / CONNECTIONS while connected / NOBODY). The author
     * may always answer on their own shared post (an asker replying to answers).
     */
    public static boolean canComment(PostFacts facts, CommentPolicy policy) {
        if (!canSeePost(facts) || facts.audience() == Audience.ONLY_ME) return false;
        if (!facts.moderation().visibleToOthers()) return false;
        if (facts.people().self()) return true;
        return switch (policy) {
            case EVERYONE -> true;
            case CONNECTIONS -> facts.connected();
            case NOBODY -> false;
        };
    }

    /**
     * Phase 13D: whether the viewer may see one perspective on a post they can see. Its writer always (marked as hidden,
     * waiting, held or removed when it is). Anyone else needs to be able to see the writer's profile, and the
     * perspective must not be held or removed by a moderator; one the post's author hid (Q5) or has not approved yet
     * (Q6) is seen by the post's author only.
     */
    public static boolean canSeeComment(CommentFacts facts) {
        if (facts.writer().self()) return true;
        if (!canSeeProfile(facts.writer())) return false;
        if (!facts.moderation().visibleToOthers()) return false;
        if (facts.hiddenByAuthor() || facts.awaitingApproval()) return facts.postAuthor();
        return true;
    }

    /**
     * The audiences of someone's posts this viewer may see once the delay is over: the same rule as [canSeePost], asked
     * for each audience, so that list queries can filter in the database without a second definition.
     */
    public static Set<Audience> visibleAudiences(Facts people, boolean connected) {
        Set<Audience> audiences = EnumSet.noneOf(Audience.class);
        for (Audience audience : Audience.values()) {
            if (canSeePost(new PostFacts(people, connected, audience, true))) audiences.add(audience);
        }
        return audiences;
    }

    /** Phase 13D: the moderation states of someone's posts this viewer may see (the same rule, for list queries). */
    public static Set<ModerationState> visibleStates(Facts people) {
        Set<ModerationState> states = EnumSet.noneOf(ModerationState.class);
        for (ModerationState state : ModerationState.values()) {
            if (canSeePost(new PostFacts(people, true, Audience.EVERYONE, true, state))) states.add(state);
        }
        return states;
    }
}
