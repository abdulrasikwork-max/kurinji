package com.safesphere.app.core.tracking

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.safesphere.app.MainActivity
import com.safesphere.app.R
import com.safesphere.app.SafeSphereApp
import com.safesphere.app.core.notifications.NotificationChannels
import com.safesphere.app.core.notifications.NotificationPermission
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Which foreground service types the ONE service needs right now (Phase 5): location while an emergency or walk tracks
 * location (and permission is granted), microphone while audio evidence is recorded (and RECORD_AUDIO is granted).
 * Null = nothing to do. On Android 9 (no types) the value is 0, which still means "run".
 */
internal object ForegroundTypes {
    fun current(context: Context, container: com.safesphere.app.AppContainer): Int? {
        val location = container.locationTrackingNeeded() && hasPermission(context, android.Manifest.permission.ACCESS_COARSE_LOCATION)
        val microphone = container.evidenceRecorder.state.value.isRecording &&
            hasPermission(context, android.Manifest.permission.RECORD_AUDIO)
        if (!location && !microphone) return null
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return 0
        var types = 0
        if (location) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        if (microphone && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        return types
    }

    fun hasMicrophone(types: Int): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && (types and ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE) != 0

    private fun hasPermission(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == android.content.pm.PackageManager.PERMISSION_GRANTED
}

/**
 * The ONE foreground service (types "location" and, since Phase 5, "microphone") that keeps SafeSphere's location
 * updates and an audio evidence recording running while
 * the screen is off or another app is open. It does no tracking itself: the work happens in the
 * shared holder ([TrackingNoticeSource], here the emergency location sharing). This service only keeps
 * the process in the foreground and shows the required ongoing notification, and it stops itself as soon
 * as the source has nothing left to track.
 *
 * Platform rules respected:
 *  - started only from a visible screen (Android 12+ forbids starting it from the background);
 *  - started only after location permission was granted (Android 14+ throws otherwise);
 *  - START_NOT_STICKY: Android does not restart it after the process dies; the emergency is recovered
 *    the next time the app is opened instead (a restarted service would have no location access anyway,
 *    because background location is never requested).
 */
class SafetyTrackingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observeJob: Job? = null

    private val container get() = (application as SafeSphereApp).container

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val source = container.trackingNoticeSource
        // startForeground must be called promptly after startForegroundService(), even if we stop right away.
        val notice = source.currentNotice() ?: TrackingNotice(R.string.tracking_notification_title, R.string.tracking_notification_starting)
        val types = ForegroundTypes.current(this, container)
        val started = types != null && promote(notice, types)
        if (!started || source.currentNotice() == null) {
            stopTracking()
            return START_NOT_STICKY
        }
        container.trackingServiceRunning.value = true

        if (observeJob == null) {
            observeJob = scope.launch {
                source.notices.collect { current ->
                    if (current == null) {
                        stopTracking()
                        return@collect
                    }
                    // Phase 5: recording audio adds the microphone type (and stopping removes it); this happens right
                    // after the person tapped "Record" on the visible SOS screen, when Android allows it.
                    val wanted = ForegroundTypes.current(this@SafetyTrackingService, container)
                    when {
                        wanted == null -> stopTracking()
                        wanted != activeTypes -> if (!promote(current, wanted)) updateNotification(current)
                        else -> updateNotification(current)
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        container.trackingServiceRunning.value = false
        container.microphoneInForeground.value = false
        scope.cancel()
        super.onDestroy()
    }

    /** (Re)enters the foreground with [types]. False when Android refuses (missing permission, background start). */
    private fun promote(notice: TrackingNotice, types: Int): Boolean = try {
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(notice), types)
        activeTypes = types
        container.microphoneInForeground.value = ForegroundTypes.hasMicrophone(types)
        true
    } catch (e: Exception) {
        // SecurityException (permission missing) or ForegroundServiceStartNotAllowedException (background start).
        false
    }

    private var activeTypes: Int = -1

    private fun stopTracking() {
        container.trackingServiceRunning.value = false
        container.microphoneInForeground.value = false
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun updateNotification(notice: TrackingNotice) {
        if (!NotificationPermission.isGranted(this)) return
        try {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification(notice))
        } catch (e: SecurityException) {
            // Permission revoked meanwhile: the service keeps running, the text simply is not updated.
        }
    }

    private fun buildNotification(notice: TrackingNotice): Notification {
        // Same as tapping the app icon: brings the existing task (and the screen the person was on) to the front.
        val launchIntent = Intent(this, MainActivity::class.java)
            .setAction(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        val openApp = PendingIntent.getActivity(
            this, 0, launchIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        // What the lock screen shows: no detail at all.
        val publicVersion = NotificationCompat.Builder(this, NotificationChannels.SAFETY_TRACKING)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(getString(R.string.tracking_notification_public_title))
            .build()
        return NotificationCompat.Builder(this, NotificationChannels.SAFETY_TRACKING)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(getString(notice.title))
            .setContentText(getString(notice.text))
            .setStyle(NotificationCompat.BigTextStyle().bigText(getString(notice.text)))
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        private const val NOTIFICATION_ID = 4001

        /**
         * Asks Android to start the service. Call only from a visible screen, after location permission was
         * granted. Returns false when Android refuses; the caller then tells the person to keep the app open.
         */
        fun start(context: Context): Boolean =
            try {
                ContextCompat.startForegroundService(context, Intent(context, SafetyTrackingService::class.java))
                true
            } catch (e: IllegalStateException) {
                // Includes ForegroundServiceStartNotAllowedException (Android 12+, app not in the foreground).
                false
            } catch (e: SecurityException) {
                false
            }
    }
}
