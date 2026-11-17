package com.safesphere.backend.social.entity;

/**
 * Phase N5 (V36): the closed lists of responses and plans. The V36 CHECKs use the same codes; a code is added by a
 * migration, never by free text.
 */
public final class ResponseEnums {

    private ResponseEnums() {
    }

    /** N-88, N-89: a one-tap signal on a Say. SAME = "I feel this too"; SAY_MORE = "tell me more". */
    public enum SignalKind { SAME, SAY_MORE }

    /** N-90: a person and a Who's in?. ASKED (waiting for the author), IN (added), REMOVED (cannot ask again). */
    public enum PlanState { ASKED, IN, REMOVED }

    /** N-91, N-92: a PLAN (author + the people IN) or a private REPLY (one replier and the author). */
    public enum ConversationKind { PLAN, REPLY }

    /** TEXT = words someone wrote; SYSTEM = a quiet line about what happened ([MessageEvent]). */
    public enum MessageKind { TEXT, SYSTEM }

    /** What a SYSTEM line says: someone is in, can't make it, the plan was called off, it happened, a reply ended. */
    public enum MessageEvent { IN, LEFT, CALLED_OFF, HAPPENED, ENDED }

    /** N-91: who may reply privately to a person's moments. No settings row = MY_PEOPLE. */
    public enum PrivateReplies { MY_PEOPLE, COMMUNITY, NOBODY }
}
