package com.safesphere.app.feature.social

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import org.w3c.dom.Node

/**
 * Found on a real phone (Galaxy A23, live path): "Your posts" held two posts but showed one. A wrap_content,
 * non-scrolling RecyclerView inside a ScrollView lays out only about one screen of items, and a photo card is nearly a
 * screen tall, so the second post could not be reached. The three screens that list people's posts must therefore keep
 * a RecyclerView that scrolls by itself (never nested in a ScrollView, never wrap_content, nested scrolling not turned
 * off); a header above the list goes in an AppBarLayout, as on Discover.
 */
class PostListLayoutTest {

    private val screens = listOf("fragment_my_posts.xml", "fragment_person_profile.xml", "fragment_post_collection.xml")

    @Test
    fun `the post list of each screen scrolls by itself`() {
        for (name in screens) {
            val list = postList(name)
            assertNotNull("$name: no RecyclerView with id postList", list)
            list!!
            assertFalse("$name: postList sits inside a ScrollView (it would lay out one screen of items only)",
                ancestors(list).any { it.tagName in SCROLL_PARENTS })
            assertTrue("$name: postList must not be wrap_content",
                list.getAttribute("android:layout_height") != "wrap_content")
            assertTrue("$name: postList must keep nested scrolling",
                list.getAttribute("android:nestedScrollingEnabled") != "false")
        }
    }

    @Test
    fun `a header above the list is an AppBarLayout, so it scrolls away with the list`() {
        for (name in listOf("fragment_person_profile.xml", "fragment_post_collection.xml")) {
            val root = document(name).documentElement
            val appBars = root.getElementsByTagName("com.google.android.material.appbar.AppBarLayout")
            assertEquals("$name: one AppBarLayout header", 1, appBars.length)
            val list = postList(name)!!
            assertEquals("$name: the list uses the scrolling-view behaviour", "com.google.android.material.appbar.AppBarLayout\$ScrollingViewBehavior",
                list.getAttribute("app:layout_behavior"))
            assertEquals("$name: the list's parent is the CoordinatorLayout", "androidx.coordinatorlayout.widget.CoordinatorLayout",
                (list.parentNode as Element).tagName)
        }
    }

    @Test
    fun `the views the fragments use are still there`() {
        val ids = mapOf(
            "fragment_my_posts.xml" to listOf("postList", "shareButton", "showMoreButton", "emptyText", "progress", "errorNotice"),
            "fragment_person_profile.xml" to listOf("postList", "showMoreButton", "postsEmpty", "progress", "errorNotice",
                "contentBlock", "notFound", "shareExperienceButton"),
            "fragment_post_collection.xml" to listOf("postList", "showMoreButton", "endText", "emptyBlock", "emptyText",
                "progress", "errorNotice", "heading", "kindTag"))
        for ((name, wanted) in ids) {
            val text = file(name).readText()
            for (id in wanted) assertTrue("$name lost @+id/$id", text.contains("@+id/$id\""))
        }
    }

    // ---- helpers ----

    private fun file(name: String) = File("src/main/res/layout/$name")

    private fun document(name: String) = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file(name))

    private fun postList(name: String): Element? {
        val all = document(name).getElementsByTagName("androidx.recyclerview.widget.RecyclerView")
        for (i in 0 until all.length) {
            val e = all.item(i) as Element
            if (e.getAttribute("android:id") == "@+id/postList") return e
        }
        return null
    }

    private fun ancestors(e: Element): List<Element> {
        val list = mutableListOf<Element>()
        var node: Node? = e.parentNode
        while (node is Element) {
            list += node
            node = node.parentNode
        }
        return list
    }

    private companion object {
        val SCROLL_PARENTS = setOf("ScrollView", "androidx.core.widget.NestedScrollView", "HorizontalScrollView")
    }
}
