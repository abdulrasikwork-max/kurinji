package com.safesphere.app

import android.app.Application
import com.safesphere.app.core.notifications.NotificationChannels

class SafeSphereApp : Application() {

    /** Created lazily the first time a screen asks for it. */
    val container: AppContainer by lazy { AppContainer(this) }

    /**
     * Phase 17F: counts the screens that are started. It must exist BEFORE the first activity starts (the container is
     * created lazily, usually after that), so it is made here, in Application.onCreate.
     */
    lateinit var appVisibility: com.safesphere.app.core.lifecycle.AppVisibility
        private set

    override fun onCreate() {
        super.onCreate()
        appVisibility = com.safesphere.app.core.lifecycle.AppVisibility(this)
        // Creating channels needs no permission and posts nothing; it only registers them with Android.
        NotificationChannels.createAll(this)
    }
}
