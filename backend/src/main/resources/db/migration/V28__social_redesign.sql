-- Phase 17E (docs/PHASE17E.md): social redesign data (decisions D-3, D-5, D-7 of PHASE17_PLAN.md; E17-5..E17-8).
-- Design rules:
--   * D-3: the butterfly is now a REACTION (one row per person and post, no state); saving is a separate private
--     bookmark (saved_posts). Every 13C Cocoon row (WANT or BEEN) becomes a saved post; no reaction is invented from
--     a save, so butterflies starts empty. Nobody but the person sees their rows; the author of an Experience learns
--     only a reaction count from 3 people up (code: ButterflyTally). Saves are counted for nobody.
--   * D-5: the cities a place or a CITY post may be in come from the ONE catalog file
--     src/main/resources/cities/cities-v1.tsv (seeded below, line for line; CityCatalogTest compares them). OTHER is a
--     post-only value without a box: no place is ever in OTHER. The closed CHECK lists of V21/V22 are replaced by
--     foreign keys WITHOUT referential actions (MySQL forbids actions on columns used in CHECKs); a city is never
--     deleted. Places outside the old 12 boxes get their city from Java at start-up (SQL cannot decode a geohash).
--   * Profile photo: only the storage key of a sanitised square JPEG (a new random key per upload). No metadata, no
--     original file, no history of earlier photos.
--   * D-7: today's state, one row per person, a closed list of moods and intents (never a place or presence), and the
--     India calendar DAY it was chosen; no time column, no history (rows of earlier days are deleted).
--   * Moderation: PERSON_REMOVE_PHOTO (a moderator removed a profile photo) joins the audit lists, kind PERSON only.
--   * Every NULL case is written out (a NULL makes an IN or a comparison UNKNOWN, which a MySQL CHECK lets pass).

-- 1. Cities (D-5).
CREATE TABLE cities (
    code    VARCHAR(20)  COLLATE utf8mb4_bin NOT NULL,
    name    VARCHAR(40)  NOT NULL,
    state   VARCHAR(40)  NULL,
    lat_min DECIMAL(5,2) NULL,
    lat_max DECIMAL(5,2) NULL,
    lng_min DECIMAL(5,2) NULL,
    lng_max DECIMAL(5,2) NULL,
    PRIMARY KEY (code),
    CONSTRAINT ck_cities_code CHECK (REGEXP_LIKE(code, '^[A-Z]{2,20}$', 'c')),
    CONSTRAINT ck_cities_box CHECK (
        (code = 'OTHER' AND state IS NULL AND lat_min IS NULL AND lat_max IS NULL AND lng_min IS NULL
            AND lng_max IS NULL)
        OR (code <> 'OTHER' AND state IS NOT NULL AND lat_min IS NOT NULL AND lat_max IS NOT NULL
            AND lng_min IS NOT NULL AND lng_max IS NOT NULL
            AND lat_min < lat_max AND lng_min < lng_max
            AND lat_min BETWEEN 6 AND 38 AND lat_max BETWEEN 6 AND 38
            AND lng_min BETWEEN 68 AND 98 AND lng_max BETWEEN 68 AND 98))
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

INSERT INTO cities (code, name, state, lat_min, lat_max, lng_min, lng_max) VALUES
    ('CHENNAI', 'Chennai', 'Tamil Nadu', 12.75, 13.35, 79.95, 80.35),
    ('COIMBATORE', 'Coimbatore', 'Tamil Nadu', 10.85, 11.20, 76.80, 77.15),
    ('MADURAI', 'Madurai', 'Tamil Nadu', 9.80, 10.05, 77.98, 78.25),
    ('TIRUCHIRAPPALLI', 'Tiruchirappalli', 'Tamil Nadu', 10.70, 10.92, 78.58, 78.82),
    ('SALEM', 'Salem', 'Tamil Nadu', 11.55, 11.78, 78.05, 78.25),
    ('TIRUNELVELI', 'Tirunelveli', 'Tamil Nadu', 8.62, 8.80, 77.62, 77.82),
    ('VELLORE', 'Vellore', 'Tamil Nadu', 12.85, 13.00, 79.05, 79.22),
    ('ERODE', 'Erode', 'Tamil Nadu', 11.27, 11.42, 77.65, 77.80),
    ('THOOTHUKUDI', 'Thoothukudi', 'Tamil Nadu', 8.70, 8.85, 78.05, 78.20),
    ('THANJAVUR', 'Thanjavur', 'Tamil Nadu', 10.72, 10.86, 79.05, 79.22),
    ('PUDUCHERRY', 'Puducherry', 'Puducherry', 11.85, 12.05, 79.70, 79.90),
    ('BENGALURU', 'Bengaluru', 'Karnataka', 12.75, 13.20, 77.35, 77.85),
    ('KANCHIPURAM', 'Kanchipuram', 'Tamil Nadu', 12.78, 12.88, 79.65, 79.76),
    ('MAMALLAPURAM', 'Mamallapuram', 'Tamil Nadu', 12.57, 12.67, 80.14, 80.24),
    ('KUMBAKONAM', 'Kumbakonam', 'Tamil Nadu', 10.92, 11.00, 79.33, 79.43),
    ('DINDIGUL', 'Dindigul', 'Tamil Nadu', 10.32, 10.40, 77.93, 78.02),
    ('KODAIKANAL', 'Kodaikanal', 'Tamil Nadu', 10.20, 10.28, 77.44, 77.53),
    ('OOTY', 'Ooty', 'Tamil Nadu', 11.36, 11.45, 76.65, 76.75),
    ('TIRUPPUR', 'Tiruppur', 'Tamil Nadu', 11.05, 11.17, 77.28, 77.40),
    ('NAGERCOIL', 'Nagercoil', 'Tamil Nadu', 8.12, 8.24, 77.36, 77.47),
    ('RAMESWARAM', 'Rameswaram', 'Tamil Nadu', 9.25, 9.32, 79.25, 79.35),
    ('MUMBAI', 'Mumbai', 'Maharashtra', 18.88, 19.30, 72.77, 73.05),
    ('DELHI', 'Delhi', 'Delhi', 28.40, 28.88, 76.84, 77.45),
    ('KOLKATA', 'Kolkata', 'West Bengal', 22.40, 22.75, 88.20, 88.50),
    ('HYDERABAD', 'Hyderabad', 'Telangana', 17.20, 17.60, 78.25, 78.70),
    ('PUNE', 'Pune', 'Maharashtra', 18.40, 18.70, 73.70, 74.05),
    ('AHMEDABAD', 'Ahmedabad', 'Gujarat', 22.90, 23.15, 72.45, 72.70),
    ('SURAT', 'Surat', 'Gujarat', 21.05, 21.28, 72.70, 72.95),
    ('VADODARA', 'Vadodara', 'Gujarat', 22.22, 22.40, 73.08, 73.28),
    ('RAJKOT', 'Rajkot', 'Gujarat', 22.24, 22.36, 70.73, 70.87),
    ('JAIPUR', 'Jaipur', 'Rajasthan', 26.78, 27.05, 75.65, 75.95),
    ('JODHPUR', 'Jodhpur', 'Rajasthan', 26.18, 26.32, 72.95, 73.10),
    ('UDAIPUR', 'Udaipur', 'Rajasthan', 24.52, 24.65, 73.64, 73.78),
    ('LUCKNOW', 'Lucknow', 'Uttar Pradesh', 26.72, 26.98, 80.82, 81.08),
    ('KANPUR', 'Kanpur', 'Uttar Pradesh', 26.35, 26.55, 80.20, 80.45),
    ('AGRA', 'Agra', 'Uttar Pradesh', 27.10, 27.25, 77.92, 78.10),
    ('VARANASI', 'Varanasi', 'Uttar Pradesh', 25.25, 25.40, 82.90, 83.05),
    ('PRAYAGRAJ', 'Prayagraj', 'Uttar Pradesh', 25.38, 25.50, 81.78, 81.92),
    ('MEERUT', 'Meerut', 'Uttar Pradesh', 28.93, 29.03, 77.65, 77.76),
    ('NAGPUR', 'Nagpur', 'Maharashtra', 21.03, 21.25, 78.95, 79.20),
    ('NASHIK', 'Nashik', 'Maharashtra', 19.92, 20.08, 73.70, 73.88),
    ('SAMBHAJINAGAR', 'Chhatrapati Sambhajinagar', 'Maharashtra', 19.82, 19.95, 75.25, 75.42),
    ('KOLHAPUR', 'Kolhapur', 'Maharashtra', 16.65, 16.75, 74.18, 74.30),
    ('INDORE', 'Indore', 'Madhya Pradesh', 22.62, 22.82, 75.75, 75.98),
    ('BHOPAL', 'Bhopal', 'Madhya Pradesh', 23.15, 23.35, 77.30, 77.52),
    ('JABALPUR', 'Jabalpur', 'Madhya Pradesh', 23.10, 23.25, 79.88, 80.02),
    ('GWALIOR', 'Gwalior', 'Madhya Pradesh', 26.15, 26.28, 78.10, 78.25),
    ('RAIPUR', 'Raipur', 'Chhattisgarh', 21.17, 21.33, 81.55, 81.72),
    ('PATNA', 'Patna', 'Bihar', 25.55, 25.66, 85.00, 85.30),
    ('RANCHI', 'Ranchi', 'Jharkhand', 23.26, 23.45, 85.22, 85.40),
    ('BHUBANESWAR', 'Bhubaneswar', 'Odisha', 20.20, 20.40, 85.72, 85.92),
    ('VISAKHAPATNAM', 'Visakhapatnam', 'Andhra Pradesh', 17.60, 17.85, 83.10, 83.40),
    ('VIJAYAWADA', 'Vijayawada', 'Andhra Pradesh', 16.45, 16.58, 80.57, 80.72),
    ('GUNTUR', 'Guntur', 'Andhra Pradesh', 16.25, 16.36, 80.38, 80.50),
    ('TIRUPATI', 'Tirupati', 'Andhra Pradesh', 13.57, 13.70, 79.35, 79.48),
    ('WARANGAL', 'Warangal', 'Telangana', 17.90, 18.05, 79.50, 79.65),
    ('KOCHI', 'Kochi', 'Kerala', 9.85, 10.10, 76.20, 76.40),
    ('THIRUVANANTHAPURAM', 'Thiruvananthapuram', 'Kerala', 8.42, 8.62, 76.85, 77.02),
    ('KOZHIKODE', 'Kozhikode', 'Kerala', 11.18, 11.33, 75.73, 75.86),
    ('THRISSUR', 'Thrissur', 'Kerala', 10.47, 10.58, 76.15, 76.27),
    ('ALAPPUZHA', 'Alappuzha', 'Kerala', 9.45, 9.55, 76.30, 76.38),
    ('MUNNAR', 'Munnar', 'Kerala', 10.05, 10.12, 77.03, 77.10),
    ('MYSURU', 'Mysuru', 'Karnataka', 12.22, 12.38, 76.55, 76.72),
    ('MANGALURU', 'Mangaluru', 'Karnataka', 12.82, 13.00, 74.78, 74.92),
    ('HUBBALLI', 'Hubballi', 'Karnataka', 15.30, 15.42, 75.05, 75.20),
    ('BELAGAVI', 'Belagavi', 'Karnataka', 15.80, 15.92, 74.45, 74.56),
    ('MADIKERI', 'Madikeri', 'Karnataka', 12.39, 12.45, 75.70, 75.78),
    ('HAMPI', 'Hampi', 'Karnataka', 15.30, 15.36, 76.42, 76.50),
    ('GOA', 'Goa', 'Goa', 15.00, 15.80, 73.65, 74.30),
    ('CHANDIGARH', 'Chandigarh', 'Chandigarh', 30.65, 30.80, 76.68, 76.88),
    ('LUDHIANA', 'Ludhiana', 'Punjab', 30.82, 30.98, 75.75, 75.98),
    ('AMRITSAR', 'Amritsar', 'Punjab', 31.57, 31.70, 74.80, 74.95),
    ('JAMMU', 'Jammu', 'Jammu and Kashmir', 32.66, 32.80, 74.78, 74.95),
    ('SRINAGAR', 'Srinagar', 'Jammu and Kashmir', 34.00, 34.15, 74.70, 74.90),
    ('LEH', 'Leh', 'Ladakh', 34.12, 34.20, 77.53, 77.62),
    ('SHIMLA', 'Shimla', 'Himachal Pradesh', 31.05, 31.15, 77.10, 77.25),
    ('MANALI', 'Manali', 'Himachal Pradesh', 32.20, 32.28, 77.15, 77.22),
    ('DEHRADUN', 'Dehradun', 'Uttarakhand', 30.25, 30.40, 77.95, 78.12),
    ('RISHIKESH', 'Rishikesh', 'Uttarakhand', 30.05, 30.14, 78.24, 78.32),
    ('HARIDWAR', 'Haridwar', 'Uttarakhand', 29.90, 29.99, 78.10, 78.20),
    ('GUWAHATI', 'Guwahati', 'Assam', 26.05, 26.25, 91.60, 91.90),
    ('SHILLONG', 'Shillong', 'Meghalaya', 25.53, 25.62, 91.84, 91.95),
    ('GANGTOK', 'Gangtok', 'Sikkim', 27.29, 27.37, 88.57, 88.65),
    ('DARJEELING', 'Darjeeling', 'West Bengal', 27.00, 27.07, 88.23, 88.30),
    ('SILIGURI', 'Siliguri', 'West Bengal', 26.68, 26.77, 88.36, 88.46),
    ('OTHER', 'Another city', NULL, NULL, NULL, NULL, NULL);

ALTER TABLE places DROP CHECK ck_places_city;
ALTER TABLE places
    ADD CONSTRAINT ck_places_city CHECK (city IS NULL OR city <> 'OTHER'),
    ADD CONSTRAINT fk_places_city FOREIGN KEY (city) REFERENCES cities (code);

ALTER TABLE posts DROP CHECK ck_posts_city;
ALTER TABLE posts
    ADD CONSTRAINT ck_posts_city CHECK ((location_precision = 'CITY') = (city IS NOT NULL)),
    ADD CONSTRAINT fk_posts_city FOREIGN KEY (city) REFERENCES cities (code);

-- 2. Saved posts, and the butterfly as a reaction (D-3).
CREATE TABLE saved_posts (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    user_id    BIGINT      NOT NULL,                          -- the person whose private list this is
    post_id    BIGINT      NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_saved_posts_user_post UNIQUE (user_id, post_id),
    CONSTRAINT fk_saved_posts_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_saved_posts_post FOREIGN KEY (post_id) REFERENCES posts (id) ON DELETE CASCADE,
    INDEX idx_saved_posts_list (user_id, created_at, id),
    INDEX idx_saved_posts_post (post_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

INSERT INTO saved_posts (user_id, post_id, created_at)
SELECT user_id, post_id, created_at FROM butterflies;

DELETE FROM butterflies;

ALTER TABLE butterflies
    DROP CHECK ck_butterflies_state,
    DROP CHECK ck_butterflies_been;
ALTER TABLE butterflies
    DROP INDEX idx_butterflies_cocoon,
    DROP COLUMN state,
    DROP COLUMN been_at;

-- 3. Profile photo.
ALTER TABLE profiles
    ADD COLUMN photo_key CHAR(36) COLLATE utf8mb4_bin NULL AFTER city,
    ADD CONSTRAINT uk_profiles_photo_key UNIQUE (photo_key),
    ADD CONSTRAINT ck_profiles_photo_key CHECK (photo_key IS NULL
        OR REGEXP_LIKE(photo_key, '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$', 'c'));

-- 4. Today's state (D-7).
CREATE TABLE daily_states (
    user_id BIGINT      NOT NULL,
    state   VARCHAR(12) COLLATE utf8mb4_bin NOT NULL,
    set_on  DATE        NOT NULL,                             -- the India (IST) day it was chosen; no time
    PRIMARY KEY (user_id),
    CONSTRAINT fk_daily_states_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT ck_daily_states_state CHECK (state IN ('CURIOUS', 'PLANNING', 'INSPIRED', 'LEARNING', 'CELEBRATING',
        'SLOW_DAY', 'BUSY_DAY', 'QUIET_DAY')),
    INDEX idx_daily_states_day (set_on)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

-- 5. The moderation audit lists learn PERSON_REMOVE_PHOTO (a PERSON decision only).
ALTER TABLE moderation_actions DROP CHECK ck_moderation_actions_action;
ALTER TABLE moderation_actions DROP CHECK ck_moderation_actions_kind_matches;
ALTER TABLE moderation_actions
    ADD CONSTRAINT ck_moderation_actions_action CHECK (action IN ('QUARANTINE', 'DISMISS', 'REMOVE', 'RESTRICT',
        'APPEAL_UPHELD', 'APPEAL_OVERTURNED', 'ROLE_GRANTED', 'ROLE_REVOKED',
        'CONTENT_KEEP', 'CONTENT_HOLD', 'CONTENT_COLLAPSE', 'CONTENT_REMOVE', 'PERSON_KEEP', 'PERSON_WARN',
        'PERSON_PAUSE_COMMENTS', 'PERSON_PAUSE_POSTING', 'PERSON_SUSPEND', 'PLACE_MERGED', 'PERSON_REMOVE_PHOTO')),
    ADD CONSTRAINT ck_moderation_actions_kind_matches CHECK (
        (action IN ('CONTENT_KEEP', 'CONTENT_HOLD', 'CONTENT_COLLAPSE', 'CONTENT_REMOVE')
            AND COALESCE(target_kind, '-') IN ('POST', 'COMMENT'))
        OR (action IN ('PERSON_KEEP', 'PERSON_WARN', 'PERSON_PAUSE_COMMENTS', 'PERSON_PAUSE_POSTING', 'PERSON_SUSPEND')
            AND COALESCE(target_kind, '-') IN ('POST', 'COMMENT', 'PERSON'))
        OR (action = 'PERSON_REMOVE_PHOTO' AND COALESCE(target_kind, '-') = 'PERSON')
        OR (action = 'PLACE_MERGED' AND COALESCE(target_kind, '-') = 'PLACE')
        OR (action IN ('APPEAL_UPHELD', 'APPEAL_OVERTURNED'))
        OR (action IN ('QUARANTINE', 'DISMISS', 'REMOVE', 'RESTRICT', 'ROLE_GRANTED', 'ROLE_REVOKED')
            AND target_kind IS NULL));
