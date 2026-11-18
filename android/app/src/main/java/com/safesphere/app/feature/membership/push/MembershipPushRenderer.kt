package com.safesphere.app.feature.membership.push

import android.content.Context
import com.safesphere.app.R
import com.safesphere.app.core.push.PushNotice
import com.safesphere.app.core.push.PushRenderer
import com.safesphere.app.core.push.PushTarget
import com.safesphere.app.core.push.Rendered
import com.safesphere.app.core.push.renderOr
import com.safesphere.app.feature.membership.domain.MembershipRepository
import com.safesphere.app.feature.membership.domain.MembershipState

/** N7 (D29): "You're verified" / "Your verification needs another look", from the person's own status. */
class MembershipPushRenderer(private val context: Context, private val membership: MembershipRepository) {

    val renderer = PushRenderer { _ ->
        membership.status().renderOr { status ->
            val title = when (status.state) {
                MembershipState.MEMBER -> context.getString(R.string.push_verified)
                MembershipState.PENDING -> return@renderOr Rendered.Nothing // not decided after all
                else -> context.getString(R.string.push_verification_again)
            }
            Rendered.Show(PushNotice(title, null, "membership", PushTarget.Membership,
                context.getString(R.string.push_public_account)))
        }
    }
}
