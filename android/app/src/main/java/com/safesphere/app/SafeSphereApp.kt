package com.safesphere.app

import android.app.Application
import com.safesphere.app.core.notifications.NotificationChannels

class SafeSphereApp : Application() {

    /** Created lazily the first time a screen asks for it. */
    val container: AppContainer by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        // Creating channels needs no permission and posts nothing; it only registers them with Android.
        NotificationChannels.createAll(this)
    }
}
