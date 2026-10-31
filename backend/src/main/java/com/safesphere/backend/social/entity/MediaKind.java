package com.safesphere.backend.social.entity;

/** Phase 17C (V27): what a post_media row holds. Every row from before V27 is a PHOTO. */
public enum MediaKind {
    PHOTO,
    /** An MP4 (Mp4Sanitizer) under "<key>.mp4" and its poster frame under "<key>.jpg" (ImageSanitizer). */
    VIDEO
}
