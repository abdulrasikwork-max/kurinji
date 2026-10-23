package com.safesphere.backend.social.entity;

/**
 * Phase 13B (V21): the closed lists of a post and its comments. The V21 CHECKs use the same codes; a code is added by a
 * migration, never by free text.
 */
public final class PostEnums {

    private PostEnums() {
    }

    /** TAKE is reserved for V2 (D8) and refused. */
    public enum PostType { EXPERIENCE, ASK }

    /** How much of the place others learn: the place, its ~1 km area, the city, or nothing. Narrowest last. */
    public enum Precision { EXACT, AREA, CITY, NONE }

    /** Who may see a post. Narrowest last. */
    public enum Audience { EVERYONE, CONNECTIONS, ONLY_ME }

    /** Who may add a Perspective. */
    public enum CommentPolicy { EVERYONE, CONNECTIONS, NOBODY }

    public enum Daypart { MORNING, AFTERNOON, EVENING, NIGHT }

    /** BEEN_HERE / QUESTION on an Experience, ANSWER / QUESTION on an Ask; REPLY only under one of those. */
    public enum CommentKind { BEEN_HERE, QUESTION, REPLY, ANSWER }

    /** Q2: the launch cities (D5) and Other. Free text would split one city into many spellings. */
    public enum City {
        CHENNAI, COIMBATORE, MADURAI, TIRUCHIRAPPALLI, SALEM, TIRUNELVELI, VELLORE, ERODE, THOOTHUKUDI, THANJAVUR,
        PUDUCHERRY, BENGALURU, OTHER
    }
}
