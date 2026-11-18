package com.safesphere.app.core.push

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.safesphere.app.SafeSphereApp
import kotlinx.coroutines.runBlocking

/**
 * N7 (D26, D27): Firebase hands every IRL push here (data-only pointers; Firebase never draws a notification itself).
 * Not exported except to Firebase's own receiver (manifest). Runs on Firebase's background thread, where a short
 * blocking read is allowed: the router renders within 8 s or shows the generic line. Nothing is logged or stored.
 */
class IrlMessagingService : FirebaseMessagingService() {

    private val container get() = (application as SafeSphereApp).container

    override fun onMessageReceived(message: RemoteMessage) {
        runBlocking { container.pushRouter.handle(message.data) }
    }

    override fun onNewToken(token: String) {
        runBlocking { container.pushRegistration.onNewToken(token) }
    }
}
