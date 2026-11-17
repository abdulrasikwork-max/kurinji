package com.safesphere.app.feature.social.ui

import androidx.fragment.app.Fragment
import coil.ImageLoader
import com.safesphere.app.appContainer
import com.safesphere.app.core.ui.components.AvatarView

/**
 * People's profile photos, through the ONE Coil loader (memory cache only, sign-in header attached: nothing about
 * other people is written to the phone). A photo is never required: without one the initial is shown.
 */
class PersonImages(
    private val loader: ImageLoader,
    private val personPhotoUrl: (profileId: String, key: String) -> String?
) {
    fun avatar(view: AvatarView, name: String?, handle: String?, profileId: String?, photo: String?) {
        val url = if (profileId != null && photo != null) personPhotoUrl(profileId, photo) else null
        view.set(name, handle, url, loader)
    }
}

fun Fragment.personImages(): PersonImages =
    PersonImages(appContainer.imageLoader, appContainer.momentRepository::personPhotoUrl)

/**
 * N4 (N-51): the programme and graduation year a verifier saw, as one short line ("B.Tech IT · 2027"). The server
 * sends the programme's code; the code is shown in a readable form, never guessed into a longer name.
 */
object CommunityLine {

    fun programme(code: String?): String? {
        val parts = code?.split('_')?.filter { it.isNotEmpty() } ?: return null
        if (parts.isEmpty()) return null
        val degree = when (parts.first()) {
            "BE" -> "B.E."
            "BTECH" -> "B.Tech"
            "ME" -> "M.E."
            "MTECH" -> "M.Tech"
            else -> parts.first()
        }
        return (listOf(degree) + parts.drop(1)).joinToString(" ")
    }

    /** Null when there is neither (no verified membership). */
    fun of(programme: String?, graduationYear: Int?): String? =
        listOfNotNull(programme(programme), graduationYear?.toString()).takeIf { it.isNotEmpty() }?.joinToString(" · ")
}
