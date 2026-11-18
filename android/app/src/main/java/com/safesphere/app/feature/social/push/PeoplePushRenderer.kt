package com.safesphere.app.feature.social.push

import android.content.Context
import com.safesphere.app.R
import com.safesphere.app.core.push.PushNotice
import com.safesphere.app.core.push.PushRenderer
import com.safesphere.app.core.push.PushTarget
import com.safesphere.app.core.push.Rendered
import com.safesphere.app.core.push.renderOr
import com.safesphere.app.feature.social.domain.SocialRepository

/**
 * N7 (D29): "Asha wants to connect". Reads the person's own incoming requests (the newest); none left (accepted,
 * declined, withdrawn, blocked) → nothing.
 */
class PeoplePushRenderer(private val context: Context, private val social: SocialRepository) {

    val renderer = PushRenderer { _ ->
        social.incomingRequests().renderOr { requests ->
            val newest = requests.firstOrNull() ?: return@renderOr Rendered.Nothing
            Rendered.Show(PushNotice(context.getString(R.string.push_people_request, newest.person.displayName),
                null, "people-requests", PushTarget.Requests, context.getString(R.string.push_public_people)))
        }
    }
}
