package com.safesphere.app.feature.social.ui

import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.feature.social.domain.DiscoverRepository
import com.safesphere.app.feature.social.domain.Post
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Phase 17E (D-3): the butterfly and Save for any list of posts (place and category pages, profiles). The change is
 * shown at once through [change] and put back if the server says no; the server is idempotent, so a double tap or a
 * lost answer is harmless, and a mark already on its way is not sent twice.
 */
class PostMarks(
    private val repository: DiscoverRepository,
    private val scope: CoroutineScope,
    /** Applies a change to the post with the given id wherever the list holds it. */
    private val change: (postId: String, (Post) -> Post) -> Unit,
    private val onFailure: (ApiResult.Failure) -> Unit
) {
    private val inFlight = HashSet<String>()

    fun react(post: Post) = run("react:" + post.publicId, post.publicId,
        apply = { it.copy(youReacted = !post.youReacted) }, undo = { it.copy(youReacted = post.youReacted) }) {
        if (post.youReacted) repository.unreact(post.publicId) else repository.react(post.publicId)
    }

    fun save(post: Post) = run("save:" + post.publicId, post.publicId,
        apply = { it.copy(saved = !post.saved) }, undo = { it.copy(saved = post.saved) }) {
        if (post.saved) repository.unsave(post.publicId) else repository.save(post.publicId)
    }

    private fun run(key: String, postId: String, apply: (Post) -> Post, undo: (Post) -> Post,
                    call: suspend () -> ApiResult<Unit>) {
        if (!inFlight.add(key)) return
        change(postId, apply)
        scope.launch {
            val result = call()
            inFlight.remove(key)
            if (result is ApiResult.Failure) {
                change(postId, undo)
                onFailure(result)
            }
        }
    }
}
