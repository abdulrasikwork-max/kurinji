package com.safesphere.backend.social.event;

/**
 * Phase 13E (docs/PHASE13E_PLAN.md E6): published by social, synchronously inside the write transaction, when a post or
 * perspective that someone else can see is created or its words change. Whoever listens (the moderation package's
 * automatic check) may change the content's moderation state before the answer is built. Social never knows who
 * listens: it depends on nothing outside itself (SocialIsolationTest).
 *
 * The listener may set [supportSuggested] (a self-harm signal in the author's own words): social then adds one calm
 * support line to THIS answer only. It is never stored by anyone.
 */
public final class ContentWritten {

    public enum Kind { POST, COMMENT }

    private final Kind kind;
    private final long contentId;
    private final long authorId;
    private final boolean probation;
    private final boolean publicPhotos;
    private final String text;
    private boolean supportSuggested;

    public ContentWritten(Kind kind, long contentId, long authorId, boolean probation, boolean publicPhotos,
                          String text) {
        this.kind = kind;
        this.contentId = contentId;
        this.authorId = authorId;
        this.probation = probation;
        this.publicPhotos = publicPhotos;
        this.text = text;
    }

    /** The words that were written: a post's body and tip on separate lines, or a perspective. */
    public static String postText(String body, String tip) {
        return tip == null ? body : body + "\n" + tip;
    }

    public void suggestSupport() {
        this.supportSuggested = true;
    }

    public Kind kind() {
        return kind;
    }

    public long contentId() {
        return contentId;
    }

    public long authorId() {
        return authorId;
    }

    /** The author is a new account (13D probation): while the check is unavailable their content waits. */
    public boolean probation() {
        return probation;
    }

    /** A post for everyone with photos (a new account's such post is looked at by a moderator first, §8.5). */
    public boolean publicPhotos() {
        return publicPhotos;
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
        return "ContentWritten[" + kind + " " + contentId + "]";
    }
}
