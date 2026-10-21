package com.safesphere.app.core.notifications

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * POST_NOTIFICATIONS is a runtime permission only on Android 13+ (API 33). Older versions allow
 * notifications by default. If it is denied, the tracking service still runs; Android just hides its
 * notification from the shade (it stays visible in the foreground-services list).
 */
object NotificationPermission {

    const val PERMISSION = Manifest.permission.POST_NOTIFICATIONS

    fun isRuntimePermission(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    fun isGranted(context: Context): Boolean =
        !isRuntimePermission() ||
            ContextCompat.checkSelfPermission(context, PERMISSION) == PackageManager.PERMISSION_GRANTED
}
