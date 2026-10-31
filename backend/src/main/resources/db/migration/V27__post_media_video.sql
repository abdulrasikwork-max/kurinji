-- Phase 17C (docs/PHASE17C.md): a post's media can be photos or one video (decision D-6).
-- Design rules:
--   * kind: PHOTO (every row before V27) or VIDEO. Binary collation, so 'photo' or 'Video' can never be stored.
--   * duration_ms: only for a VIDEO, 1 ms to 60.5 s (60 s plus half a second of container rounding).
--   * poster_bytes: a VIDEO's still frame (made on the phone, re-encoded by the server like any photo, stored under the
--     SAME storage key as "<key>.jpg" next to "<key>.mp4"). NULL until the poster arrives; a video without its poster is
--     never attached to a post (code: PostService.attach; MySQL forbids a CHECK on the cascading post_id column).
--   * Sizes per kind: a photo at most 5 MiB (unchanged), a video at most 50 MiB, its poster at most 5 MiB.
--   * Every NULL case is written out: a NULL makes a comparison UNKNOWN, which a MySQL CHECK lets pass (V23 lesson).
--   * Nothing about the video's content, place, device or recording time is stored; the file itself is stripped
--     (Mp4Sanitizer) before it is written.
ALTER TABLE post_media
    ADD COLUMN kind VARCHAR(5) COLLATE utf8mb4_bin NOT NULL DEFAULT 'PHOTO' AFTER sort_order,
    ADD COLUMN duration_ms INT NULL AFTER bytes,
    ADD COLUMN poster_bytes INT NULL AFTER duration_ms;

ALTER TABLE post_media
    DROP CHECK ck_post_media_size;

ALTER TABLE post_media
    ADD CONSTRAINT ck_post_media_kind CHECK (kind IN ('PHOTO', 'VIDEO')),
    ADD CONSTRAINT ck_post_media_size CHECK (
        width BETWEEN 1 AND 4096 AND height BETWEEN 1 AND 4096
        AND (
            (kind = 'PHOTO' AND bytes BETWEEN 1 AND 5242880 AND duration_ms IS NULL AND poster_bytes IS NULL)
            OR (kind = 'VIDEO' AND bytes BETWEEN 1 AND 52428800
                AND duration_ms IS NOT NULL AND duration_ms BETWEEN 1 AND 60500
                AND (poster_bytes IS NULL OR poster_bytes BETWEEN 1 AND 5242880))
        ));
