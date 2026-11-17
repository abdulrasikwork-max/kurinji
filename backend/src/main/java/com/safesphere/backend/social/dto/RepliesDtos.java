package com.safesphere.backend.social.dto;

import com.safesphere.backend.social.dto.MomentDtos.Person;
import com.safesphere.backend.social.dto.MomentDtos.Plan;
import java.time.Instant;
import java.util.List;

/**
 * Phase N5 (D19, N-93): Replies, the one home for what people said back. Four short sections; nothing in them is a
 * number, a "seen" or a read state. [fresh] compares with the person's OWN "last opened Replies" time only.
 */
public final class RepliesDtos {

    private RepliesDtos() {
    }

    /**
     * GET /api/replies. [needsYou]: people asking to join your plan (ASKS), asking you to say more (SAY_MORE), a private
     * reply waiting for your answer (YOUR_TURN), a follow-up you asked for (MORE). [plans]: plans you made or are in
     * (PLAN), and where your asks stand (WAITING, NOT_IN, CLOSED, CALLED_OFF). [conversations]: your other private
     * replies (REPLY). [same]: who feels the same about your moments on Today (SAME; never lights the marker).
     */
    public record RepliesPage(List<ReplyItem> needsYou, List<ReplyItem> plans, List<ReplyItem> conversations,
                              List<ReplyItem> same, boolean fresh) {
    }

    /**
     * One line of Replies. [type] as above; [moment] what it is about; [conversationId] the plan or private reply to open;
     * [people] the names it is about (at most 3; [morePeople] = and others); [turn] in a conversation (YOUR_TURN, WAITING,
     * OPEN, CLOSED, ENDED); [open]; [at] the last activity of a plan or a private reply (members only, N-94), else null;
     * [fresh] = something arrived since the person last opened Replies.
     */
    public record ReplyItem(String type, MomentBrief moment, String conversationId, List<Person> people,
                            boolean morePeople, String turn, boolean open, Instant at, boolean fresh) {
    }

    /** The moment a line is about, briefly. [mine] = the viewer wrote it; [live] = still on Today. */
    public record MomentBrief(String id, String kind, String text, Plan plan, boolean calledOff, boolean mine,
                              String authorName, boolean live) {
    }

    /** GET /api/replies/marker: [fresh] = the dot on the Replies tab (D19). */
    public record Marker(boolean fresh) {
    }
}
