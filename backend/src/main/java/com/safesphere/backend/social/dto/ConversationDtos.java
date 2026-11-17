package com.safesphere.backend.social.dto;

import com.safesphere.backend.social.dto.MomentDtos.Notice;
import com.safesphere.backend.social.dto.MomentDtos.Person;
import com.safesphere.backend.social.dto.MomentDtos.Plan;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

/**
 * Phase N5: plans and private replies as the API returns them. Exact times are given here because only the members of a
 * conversation get them (N-94). Nothing says whether or when anyone read anything (N-93).
 */
public final class ConversationDtos {

    private ConversationDtos() {
    }

    /** Body of POST /api/moments/{id}/private-replies and POST /api/conversations/{id}/messages: 1 to 500 characters. */
    public record TextRequest(@Size(max = 4000, message = "A message has at most 500 characters") String text) {
    }

    /**
     * One conversation for one of its members. [kind] PLAN | REPLY; [moment] what it is about; [people] for a PLAN the
     * author and the people in it, for a REPLY the other person; [messages] oldest first; [you] what the viewer may do;
     * [closesAt] when it becomes read-only; [open].
     */
    public record ConversationView(String id, String kind, MomentRef moment, List<Person> people,
                                   List<MessageView> messages, You you, Instant closesAt, boolean open) {
    }

    /**
     * The moment a conversation is about, briefly: [text] its words (may be null next to a photo), [plan] a Who's in?'s
     * time and "where", [calledOff], [mine] = the viewer wrote it, [authorName].
     */
    public record MomentRef(String id, String kind, String text, Plan plan, boolean calledOff, boolean mine,
                            String authorName) {
    }

    /**
     * One message. [kind] TEXT | SYSTEM; [text] the words (TEXT); [event] IN | LEFT | CALLED_OFF | HAPPENED | ENDED
     * (SYSTEM); [by] who wrote it or who it is about; [mine]; [at] the exact time (members only); [collapsed] = shown to
     * others behind a tap; [notice] = what the WRITER is told about their own message (never anyone else);
     * [supportSuggested] only in the writer's own answer to sending.
     */
    public record MessageView(String id, String kind, String text, String event, Person by, boolean mine, Instant at,
                              boolean collapsed, Notice notice, Boolean supportSuggested) {

        public MessageView withSupport() {
            return new MessageView(id, kind, text, event, by, mine, at, collapsed, notice, Boolean.TRUE);
        }
    }

    /** [turn] YOUR_TURN | WAITING | OPEN | CLOSED | ENDED; [canSend]; [paused] = a responses pause stops sending. */
    public record You(String turn, boolean canSend, boolean paused) {
    }

    /** GET / PUT /api/social-settings: [privateReplies] MY_PEOPLE | COMMUNITY | NOBODY (N-91). */
    public record Settings(@Size(max = 12, message = "Choose who may reply privately") String privateReplies) {
    }
}
