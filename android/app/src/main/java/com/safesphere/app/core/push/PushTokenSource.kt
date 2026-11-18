package com.safesphere.app.core.push

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.safesphere.app.BuildConfig
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/** This phone's push token (a fake in tests). */
interface PushTokenSource {
    val configured: Boolean
    suspend fun token(): String?
    suspend fun delete()
}

/**
 * N7 (D27): Firebase set up by hand from the build's four public values (no google-services plugin, no JSON in git),
 * with automatic token creation off: a token exists only after someone signed in and notifications are allowed. No
 * Analytics. A build without the values has [configured] = false and never touches Firebase.
 */
class FirebaseTokenSource(private val context: Context) : PushTokenSource {

    override val configured: Boolean =
        listOf(BuildConfig.FIREBASE_APP_ID, BuildConfig.FIREBASE_API_KEY, BuildConfig.FIREBASE_PROJECT_ID,
            BuildConfig.FIREBASE_SENDER_ID).all { it.isNotBlank() }

    private fun messaging(): FirebaseMessaging? {
        if (!initialize(context)) return null
        return try {
            FirebaseMessaging.getInstance()
        } catch (e: IllegalStateException) {
            null
        }
    }

    companion object {
        /**
         * Sets Firebase up from the build's values (Application.onCreate: a push can start IRL in the background). No
         * token is created here (automatic creation is off in the manifest). False when this build has no values.
         */
        fun initialize(context: Context): Boolean {
            val values = listOf(BuildConfig.FIREBASE_APP_ID, BuildConfig.FIREBASE_API_KEY, BuildConfig.FIREBASE_PROJECT_ID,
                BuildConfig.FIREBASE_SENDER_ID)
            if (values.any { it.isBlank() }) return false
            return try {
                if (FirebaseApp.getApps(context).isEmpty()) {
                    FirebaseApp.initializeApp(context, FirebaseOptions.Builder()
                        .setApplicationId(BuildConfig.FIREBASE_APP_ID)
                        .setApiKey(BuildConfig.FIREBASE_API_KEY)
                        .setProjectId(BuildConfig.FIREBASE_PROJECT_ID)
                        .setGcmSenderId(BuildConfig.FIREBASE_SENDER_ID)
                        .build())
                }
                true
            } catch (e: IllegalStateException) {
                false
            }
        }
    }

    override suspend fun token(): String? {
        val messaging = messaging() ?: return null
        messaging.isAutoInitEnabled = true // only now, while signed in with notifications allowed
        return suspendCancellableCoroutine { cont ->
            messaging.token.addOnCompleteListener { task ->
                cont.resume(if (task.isSuccessful) task.result else null)
            }
        }
    }

    override suspend fun delete() {
        val messaging = messaging() ?: return
        messaging.isAutoInitEnabled = false
        suspendCancellableCoroutine { cont ->
            messaging.deleteToken().addOnCompleteListener { cont.resume(Unit) }
        }
    }
}
