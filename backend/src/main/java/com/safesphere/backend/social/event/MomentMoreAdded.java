package com.safesphere.backend.social.event;

/**
 * Phase N5 (N-89, N-96): published by social, synchronously inside the write transaction, when an author adds the
 * follow-up to "Say more". Whoever listens (the automatic text check) may change the MOMENT's moderation state: the
 * follow-up is part of the moment's words. Social never knows who listens (SocialIsolationTest). A listener may set
 * [supportSuggested]; it is never stored.
 */
public final class MomentMoreAdded {

    private final long momentId;
    private final long authorId;
    private final boolean newMember;
    private final String text;
    private boolean supportSuggested;

    public MomentMoreAdded(long momentId, long authorId, boolean newMember, String text) {
        this.momentId = momentId;
        this.authorId = authorId;
        this.newMember = newMember;
        this.text = text;
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

    public boolean newMember() {
        return newMember;
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
        return "MomentMoreAdded[" + momentId + "]";
    }
}
