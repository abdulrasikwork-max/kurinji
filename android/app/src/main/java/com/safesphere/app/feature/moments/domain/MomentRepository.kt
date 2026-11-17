package com.safesphere.app.feature.moments.domain

import com.safesphere.app.core.network.ApiResult

/**
 * Phase N4: moments on the backend. Nothing is stored on the phone: every screen loads what it shows when it opens.
 * A moment the viewer may not see answers NOT_FOUND, exactly like one that does not exist.
 */
interface MomentRepository {

    /** One page of Today, newest first. [cursor] = [TodayPage.nextCursor] of the page before. */
    suspend fun today(people: People, cursor: String?): ApiResult<TodayPage>

    suspend fun moment(id: String): ApiResult<Moment>

    suspend fun mine(): ApiResult<MyMoments>

    suspend fun ofProfile(profileId: String): ApiResult<ProfileMoments>

    /** Always allowed for the author. There is no edit: delete and share again. */
    suspend fun delete(id: String): ApiResult<Unit>

    /** Keeps a live moment on the profile; CONFLICT with the server's words when six are kept or it is not live. */
    suspend fun keep(id: String): ApiResult<Moment>

    suspend fun unkeep(id: String): ApiResult<Unit>

    /** A2: a JPEG re-encoded on the phone; the server re-encodes it again and keeps it until a moment uses it (24 h). */
    suspend fun uploadPhoto(jpeg: ByteArray): ApiResult<UploadedPhoto>

    /** A2: an upload no moment uses (discarded or replaced). Best effort: the server deletes it after 24 h anyway. */
    suspend fun discardPhoto(mediaId: String): ApiResult<Unit>

    /** A2: shares a moment; the answer says what happened to it (live, waiting, collapsed). */
    suspend fun share(draft: MomentDraft): ApiResult<Moment>

    /** The address of a moment's photo, or null when [momentId] is not an id (never put anything else into a URL). */
    fun photoUrl(momentId: String): String?

    /** The address of a person's profile photo, or null when either part is not an id. */
    fun personPhotoUrl(profileId: String, key: String): String?
}
