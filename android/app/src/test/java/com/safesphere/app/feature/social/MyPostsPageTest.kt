package com.safesphere.app.feature.social

import com.google.gson.Gson
import com.safesphere.app.feature.social.data.remote.PostPageDto
import com.safesphere.app.feature.social.domain.MediaKind
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * "Your posts" returned two posts from the server (a video post and a 4-photo post, both Everyone / Area) but the list
 * showed one. This pins the data layer: the page the server sends, as the phone reads it, keeps every post.
 */
class MyPostsPageTest {

    private val gson = Gson()

    private fun post(id: String, media: String, moderation: String?) = """
        {"publicId":"$id","type":"EXPERIENCE","author":{"publicId":"a1","handle":"me","displayName":"Me"},"own":true,
         "body":"Words","tip":null,"category":"FOOD_CAFES","precision":"AREA","place":null,"areaCell":"tf2c1x","city":null,
         "experiencedMonth":"2026-11","daypart":null,"postedPeriod":"TODAY","edited":false,"media":[$media],
         "audience":"EVERYONE","commentPolicy":"EVERYONE","canComment":true,"postedAt":"2026-11-11T13:32:54.100Z",
         "visibleFrom":"2026-11-11T13:32:54.100Z","youReacted":false,"saved":false,"butterflies":null,"approveFirst":false,
         "moderation":$moderation,"collapsed":null,"strongLanguage":false,"supportSuggested":null,"commentCount":0,
         "facets":[]}"""

    private val video = """{"mediaId":"m-video","width":720,"height":1280,"kind":"VIDEO","durationMs":3130}"""
    private fun photos(n: Int) = (1..n).joinToString(",") { """{"mediaId":"m-photo-$it","width":1080,"height":1440,"kind":"PHOTO","durationMs":null}""" }
    private val held = """{"state":"HELD","reason":"NEEDS_REVIEW","decisionId":null,"source":"SYSTEM","flagged":[]}"""
    private val none = """{"state":"NONE","reason":null,"decisionId":null,"source":null,"flagged":null}"""

    private fun page(vararg posts: String) = """{"items":[${posts.joinToString(",")}],"nextCursor":null}"""

    @Test
    fun `a video post and a four photo post are both kept, whatever the moderation state`() {
        for (moderation in listOf(null, "null", held, none)) {
            val m = moderation ?: "null"
            val read = gson.fromJson(page(post("p-video", video, m), post("p-photos", photos(4), m)),
                PostPageDto::class.java).toDomain()
            assertEquals("moderation=$m", listOf("p-video", "p-photos"), read.items.map { it.publicId })
            assertEquals(listOf(MediaKind.VIDEO), read.items[0].media.map { it.kind })
            assertEquals(4, read.items[1].media.size)
        }
    }

    @Test
    fun `an unknown media kind drops only that media item, never the post`() {
        val odd = """{"mediaId":"m-x","width":1,"height":1,"kind":"GIF","durationMs":null}"""
        val read = gson.fromJson(page(post("p1", odd, none), post("p2", photos(2), none)), PostPageDto::class.java).toDomain()
        assertEquals(listOf("p1", "p2"), read.items.map { it.publicId })
        assertEquals(0, read.items[0].media.size)
    }
}
