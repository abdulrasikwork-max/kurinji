package com.safesphere.app.feature.social.domain

/**
 * IRL redesign (R1): a counter in memory that says "the person's own posts changed" (shared or deleted), so a feed
 * that is still on the back stack reads again when it is shown next. Nothing is stored.
 */
class FeedFreshness {
    /** The city code the Home feed is showing now (memory only), so Explore can start in the same city. */
    @Volatile
    var cityCode: String? = null

    @Volatile
    var version: Int = 0
        private set

    fun changed() {
        version++
    }
}
