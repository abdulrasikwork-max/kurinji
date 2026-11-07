package com.safesphere.app.core.device

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.PowerManager

/**
 * How hard the phone's battery manager limits IRL when it is not on screen (real-device sprint, Track E, Galaxy A23).
 * Measured there: with Samsung's default "Optimized" a Safe Walk (location-only foreground service) was killed by the
 * phone's own process manager 45 s after IRL was swiped away from Recents; an SOS was not. With "Restricted" both the
 * walk and the SOS lost their location as soon as IRL left the screen. With "Unrestricted" both kept running.
 * The app cannot change this; it can only say so honestly. No permission is needed to read it.
 */
enum class BackgroundLimit {
    /** Exempt from battery optimisation ("Unrestricted"): nothing to say. */
    NONE,

    /** Default "Optimized": a walk may be ended by the phone when IRL is closed from the recent apps. */
    OPTIMIZED,

    /** The person (or the phone) restricted IRL's background use: walks and SOS lose location when IRL leaves the screen. */
    RESTRICTED
}

object BackgroundLimits {

    /** Pure: [restricted] = the app is background-restricted; [ignoringOptimizations] = exempt from battery optimisation. */
    fun of(restricted: Boolean, ignoringOptimizations: Boolean): BackgroundLimit = when {
        restricted -> BackgroundLimit.RESTRICTED
        ignoringOptimizations -> BackgroundLimit.NONE
        else -> BackgroundLimit.OPTIMIZED
    }

    /** Reads the two platform flags. Any failure reads as [BackgroundLimit.NONE]: a note we are not sure of is not shown. */
    fun read(context: Context): BackgroundLimit = runCatching {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val restricted = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && activityManager.isBackgroundRestricted
        of(restricted, powerManager.isIgnoringBatteryOptimizations(context.packageName))
    }.getOrDefault(BackgroundLimit.NONE)
}
