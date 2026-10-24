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

    /** What is known about a viewer and the owner of something. */
    public record Facts(boolean self, boolean blockedEitherWay, boolean ownerActive) {
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

    private Visibility() {
    }

    /**
     * A profile is visible to its owner; to anyone else only while the owner's account is active (not disabled, not
     * erased) and neither has blocked the other. Signed-out people never get here (every social endpoint needs a JWT).
     */
    public static boolean canSeeProfile(Facts facts) {
        if (facts.self()) return true;
        return facts.ownerActive() && !facts.blockedEitherWay();
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
     * a post opened deliberately (a profile visit, a link, the viewer's own Cocoon); those ask [canSeePost].
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
