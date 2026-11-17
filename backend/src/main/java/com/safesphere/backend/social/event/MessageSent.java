package com.safesphere.backend.social.event;

/**
 * Phase N5 (N-96): published by social, synchronously inside the write transaction, when someone writes in a
 * conversation, BEFORE anyone else can read it. Whoever listens (the automatic text check) may hold or collapse the
 * message; the writer's answer already shows what happened. Social never knows who listens (SocialIsolationTest).
 *
 * [firstContact] = the first words of a private reply by a new member to someone who is not their connection: while the
 * check is unavailable only these wait (at most 2 hours, N-96). A listener may set [supportSuggested]; never stored.
 */
public final class MessageSent {

    private final long messageId;
    private final long authorId;
    private final boolean firstContact;
    private final String text;
    private boolean supportSuggested;

    public MessageSent(long messageId, long authorId, boolean firstContact, String text) {
        this.messageId = messageId;
        this.authorId = authorId;
        this.firstContact = firstContact;
        this.text = text;
    }

    public void suggestSupport() {
        this.supportSuggested = true;
    }

    public long messageId() {
        return messageId;
    }

    public long authorId() {
        return authorId;
    }

    public boolean firstContact() {
        return firstContact;
    }

    public String text() {
        return text;
    }

    public boolean supportSuggested() {
        return supportSuggested;
    }

    /** No words in logs or exceptions. */
    @Override
    public String toString() {
        return "MessageSent[" + messageId + "]";
    }
}
