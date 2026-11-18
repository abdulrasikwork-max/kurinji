package com.safesphere.app.navigation

import androidx.core.os.bundleOf
import androidx.navigation.NavController
import com.safesphere.app.R
import com.safesphere.app.core.push.PushTarget

/**
 * N7 (D34): where each notification target lands. A tab first (so Back and the bar make sense), then the screen. The
 * screen itself reads IRL and shows the state as it is now (a closed plan, an ended SOS, a request already answered).
 */
object PushNavigation {

    /** A tab like the bar opens it: one copy, above Today. */
    private fun tab(nav: NavController, id: Int) {
        nav.navigate(id, null, androidx.navigation.navOptions {
            launchSingleTop = true
            popUpTo(R.id.todayFragment)
        })
    }

    fun open(nav: NavController, target: PushTarget) {
        when (target) {
            is PushTarget.Moment -> {
                tab(nav, R.id.todayFragment)
                nav.navigate(R.id.momentFragment, bundleOf("momentId" to target.id))
            }
            is PushTarget.Conversation -> {
                tab(nav, R.id.repliesFragment)
                nav.navigate(R.id.conversationFragment, bundleOf("conversationId" to target.id))
            }
            PushTarget.Replies -> tab(nav, R.id.repliesFragment)
            PushTarget.Requests -> {
                tab(nav, R.id.youFragment)
                nav.navigate(R.id.connectionsFragment)
            }
            PushTarget.Account -> {
                tab(nav, R.id.youFragment)
                nav.navigate(R.id.accountFragment)
            }
            PushTarget.Membership -> {
                tab(nav, R.id.youFragment)
                nav.navigate(R.id.membershipFragment)
            }
            PushTarget.You -> tab(nav, R.id.youFragment)
            PushTarget.Staff -> {
                tab(nav, R.id.youFragment)
                nav.navigate(R.id.moreFragment)
            }
            PushTarget.CircleRequests, is PushTarget.CircleAlert -> {
                tab(nav, R.id.youFragment)
                nav.navigate(R.id.safetyFragment)
            }
            PushTarget.OnMyWay -> {
                tab(nav, R.id.youFragment)
                nav.navigate(R.id.safeWalkFragment)
            }
        }
    }
}
