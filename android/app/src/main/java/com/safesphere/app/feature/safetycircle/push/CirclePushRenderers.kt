package com.safesphere.app.feature.safetycircle.push

import android.content.Context
import com.safesphere.app.R
import com.safesphere.app.core.network.ApiError
import com.safesphere.app.core.network.ApiResult
import com.safesphere.app.core.push.PushAction
import com.safesphere.app.core.push.PushKind
import com.safesphere.app.core.push.PushNotice
import com.safesphere.app.core.push.PushPayload
import com.safesphere.app.core.push.PushRenderer
import com.safesphere.app.core.push.PushTarget
import com.safesphere.app.core.push.Rendered
import com.safesphere.app.core.push.renderOr
import com.safesphere.app.feature.safetycircle.data.CircleInAppRepository
import com.safesphere.app.feature.safetycircle.data.CircleSosStatus
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * N7 (D36): a Safety Circle request and an SOS from your people. The SOS notification has the siren (the tray draws it
 * for this kind), the name and the start time, Call them and Call 112 (the dialer; nothing calls by itself); the lock
 * screen says only "SOS from your Safety Circle". No location is ever in a notification. If IRL cannot be read the SOS
 * still shows (neutral words, opens the SOS, Call 112); an SOS that is no longer this person's shows nothing.
 */
class CirclePushRenderers(private val context: Context, private val circles: CircleInAppRepository) {

    fun all(): Map<PushKind, PushRenderer> = mapOf(
        PushKind.CIRCLE_REQUEST to PushRenderer { _ -> request() },
        PushKind.CIRCLE_SOS to PushRenderer { p -> sos(p) }
    )

    private suspend fun request(): Rendered = circles.requests().renderOr { requests ->
        val newest = requests.firstOrNull() ?: return@renderOr Rendered.Nothing
        Rendered.Show(PushNotice(context.getString(R.string.push_circle_request_title, newest.ownerName),
            context.getString(R.string.push_circle_request_text), "circle-requests", PushTarget.CircleRequests,
            context.getString(R.string.push_public_people)))
    }

    private suspend fun sos(p: PushPayload): Rendered {
        val id = p.subject.toLongOrNull()?.takeIf { it > 0 } ?: return Rendered.Nothing
        val call112 = PushAction(context.getString(R.string.circle_sos_call_112), "112")
        val public = context.getString(R.string.push_public_circle_sos)
        return when (val r = circles.alert(id)) {
            is ApiResult.Success -> {
                val sos = r.data
                val title = when (sos.status) {
                    CircleSosStatus.ACTIVE -> context.getString(R.string.push_circle_sos_title, sos.name)
                    CircleSosStatus.SAFE -> context.getString(R.string.push_circle_sos_safe, sos.name)
                    CircleSosStatus.FALSE_ALARM -> context.getString(R.string.push_circle_sos_false_alarm, sos.name)
                    CircleSosStatus.ENDED -> context.getString(R.string.push_circle_sos_ended, sos.name)
                }
                val actions = if (sos.status == CircleSosStatus.ACTIVE) listOfNotNull(
                    sos.phoneE164?.let { PushAction(context.getString(R.string.push_call, sos.name), it) }, call112
                ) else emptyList()
                Rendered.Show(PushNotice(title, TIME.withZone(ZoneId.systemDefault()).format(sos.startedAt),
                    "circle-sos-$id", PushTarget.CircleAlert(id.toString()), public, actions))
            }
            is ApiResult.Failure -> if (r.error == ApiError.NOT_FOUND || r.error == ApiError.FORBIDDEN ||
                r.error == ApiError.UNAUTHORIZED) Rendered.Nothing
            else Rendered.Show(PushNotice(public, null, "circle-sos-$id", PushTarget.CircleAlert(id.toString()), public,
                listOf(call112)))
        }
    }

    private companion object {
        val TIME: DateTimeFormatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
    }
}
