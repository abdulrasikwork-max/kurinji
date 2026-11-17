package com.safesphere.backend.social.policy;

import com.safesphere.backend.social.entity.ResponseEnums.ConversationKind;
import com.safesphere.backend.social.entity.ResponseEnums.PrivateReplies;

/**
 * Phase N5 (N-91, N-92): the rules of plans and private replies. Pure: the caller supplies the facts; unit tested.
 */
public final class ConversationPolicy {

    public static final String NOT_YOUR_TURN = "Wait for an answer before you write again";
    public static final String CLOSED = "This conversation has closed";
    public static final String REPLIES_NOT_TAKEN = "This person does not take private replies from you";
    public static final String ALREADY_REPLIED = "You have already replied to this moment";

    /** What the viewer may do in a conversation now. */
    public enum Turn { YOUR_TURN, WAITING, OPEN, CLOSED, ENDED }

    private ConversationPolicy() {
    }

    /**
     * N-91: whether the author's setting lets someone start a private reply: My people = a mutual connection; Anyone at
     * MIET = any member who can see the moment; Nobody = nobody.
     */
    public static boolean allows(PrivateReplies setting, boolean connected) {
        return switch (setting == null ? PrivateReplies.MY_PEOPLE : setting) {
            case MY_PEOPLE -> connected;
            case COMMUNITY -> true;
            case NOBODY -> false;
        };
    }

    /**
     * Where [viewerId] stands in a conversation. A REPLY takes strict turns: nobody sends twice in a row, so the person
     * who wrote the last words waits ([lastWriterId], null = nothing written yet). A PLAN has no turns.
     */
    public static Turn turn(ConversationKind kind, boolean open, boolean ended, Long lastWriterId, long viewerId) {
        if (ended) return Turn.ENDED;
        if (!open) return Turn.CLOSED;
        if (kind == ConversationKind.PLAN) return Turn.OPEN;
        return lastWriterId != null && lastWriterId == viewerId ? Turn.WAITING : Turn.YOUR_TURN;
    }

    /** Whether [turn] lets the viewer send now. */
    public static boolean canSend(Turn turn) {
        return turn == Turn.YOUR_TURN || turn == Turn.OPEN;
    }
}
