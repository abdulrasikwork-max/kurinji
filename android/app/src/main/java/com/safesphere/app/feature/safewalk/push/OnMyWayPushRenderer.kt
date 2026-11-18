package com.safesphere.app.feature.safewalk.push

import com.safesphere.app.core.push.PushRenderer
import com.safesphere.app.core.push.Rendered
import com.safesphere.app.feature.safewalk.session.SafeWalkSession
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * N7 (D37): the server's second alarm for On my way. It is handled exactly like the phone's own alarm
 * ([SafeWalkSession.onAlarm]): "There yet?" or the nudge, with the same notification id, shown once; a walk that ended or
 * got more time shows nothing. The push itself never draws a notification.
 */
class OnMyWayPushRenderer(private val walk: SafeWalkSession) {

    val renderer = PushRenderer { p ->
        val journeyId = p.subject.toLongOrNull()?.takeIf { it > 0 } ?: return@PushRenderer Rendered.Nothing
        suspendCancellableCoroutine { cont -> walk.onAlarm(journeyId) { if (cont.isActive) cont.resume(Rendered.Nothing) } }
    }
}
