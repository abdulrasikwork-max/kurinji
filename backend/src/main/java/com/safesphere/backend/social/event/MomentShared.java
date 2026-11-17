package com.safesphere.backend.social.event;

/**
 * Phase N4: published by social, synchronously inside the write transaction, when a moment is shared. Whoever listens
 * (the moderation package: the automatic text check, the case for a held photo) may change the moment's moderation
 * state before it is published and before the answer is built. Social never knows who listens: it depends on nothing
 * outside itself (SocialIsolationTest).
 *
 * The listener may set [supportSuggested] (a self-harm signal in the author's own words): social then adds one calm
 * support line to THIS answer only. It is never stored by anyone.
 */
public final class MomentShared {

    private final long momentId;
    private final long authorId;
    private final boolean newMember;
    private final boolean photoHeld;
    private final String text;
    private boolean supportSuggested;

    public MomentShared(long momentId, long authorId, boolean newMember, boolean photoHeld, String text) {
        this.momentId = momentId;
        this.authorId = authorId;
        this.newMember = newMember;
        this.photoHeld = photoHeld;
        this.text = text;
    }

    /** The words of a moment as one text: its body and its "where" on separate lines; null when it has neither. */
    public static String words(String body, String planWhere) {
        if (body == null) return planWhere;
        return planWhere == null ? body : body + "\n" + planWhere;
    }

    public void suggestSupport() {
        this.supportSuggested = true;
    }

    public long momentId() {
        return momentId;
    }

    public long authorId() {
        return authorId;
    }

    /** N-56: the author is in their first 7 days: while the check is unavailable their words wait (at most 2 hours). */
    public boolean newMember() {
        return newMember;
    }

    /** N-57: social already holds this moment (a new member's photo for the whole community): a moderator must look. */
    public boolean photoHeld() {
        return photoHeld;
    }

    /** May be null (a photo alone). */
    public String text() {
        return text;
    }

    public boolean supportSuggested() {
        return supportSuggested;
    }

    /** No words in logs or exceptions. */
    @Override
    public String toString() {
        return "MomentShared[" + momentId + "]";
    }
}
