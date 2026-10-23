package com.safesphere.backend.social.policy;

import com.safesphere.backend.social.entity.PostEnums.Audience;
import com.safesphere.backend.social.entity.PostEnums.CommentPolicy;
import java.util.EnumSet;
import java.util.Set;

/**
 * Phase 13A: THE one rule for who can see what in the social features (EXPERIENCE_PLATFORM_DESIGN.md §11.2). Every
 * read path asks this class; nothing decides visibility on its own. Pure: the caller supplies the facts.
 *
 * 13A covers profiles. 13B extends it with posts (Everyone / Connections / Only me), their photos and comments, on the
 * same facts plus "connected" and "the delay is over" (Q1).
 */
public final class Visibility {

    /** What is known about a viewer and the owner of something. */
    public record Facts(boolean self, boolean blockedEitherWay, boolean ownerActive) {
    }

    /** Phase 13B: the facts about a viewer and one post. [visibleFromReached] = the post's visible_from has passed. */
    public record PostFacts(Facts people, boolean connected, Audience audience, boolean visibleFromReached) {
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
     * either way), the delay to be over, and the audience to include them: EVERYONE, or CONNECTIONS while connected.
     * ONLY_ME is never visible to anyone else. A photo or a comment is visible only where its post is.
     */
    public static boolean canSeePost(PostFacts facts) {
        if (facts.people().self()) return true;
        if (!canSeeProfile(facts.people()) || !facts.visibleFromReached()) return false;
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
     * Who may add a Perspective: someone who can see the post, on a post that is not ONLY_ME, as the author's comment
     * policy allows (EVERYONE / CONNECTIONS while connected / NOBODY). The author may always answer on their own shared
     * post (an asker replying to answers).
     */
    public static boolean canComment(PostFacts facts, CommentPolicy policy) {
        if (!canSeePost(facts) || facts.audience() == Audience.ONLY_ME) return false;
        if (facts.people().self()) return true;
        return switch (policy) {
            case EVERYONE -> true;
            case CONNECTIONS -> facts.connected();
            case NOBODY -> false;
        };
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
}
