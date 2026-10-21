package com.safesphere.app.core.sms

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SmsManager
import android.telephony.SmsMessage
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * [SmsSender] backed by the platform SmsManager (default SMS subscription). Long messages are split into parts; the
 * message counts as SENT only when every part was accepted, FAILED if any part failed, DELIVERED only when every part
 * has a positive delivery report. Results come back through [SmsResultReceiver] (not exported: only our own
 * PendingIntents reach it). If the process dies before the results arrive, they are lost and the attempt stays as
 * "result unknown" on the phone.
 */
class AndroidSmsSender(context: Context) : SmsSender {

    private val appContext = context.applicationContext
    private val requestCodes = AtomicInteger(1000)

    private class PartTracker(val total: Int) {
        var sent = 0
        var failed = false
        var delivered = 0
        var reportedSent = false
    }

    private val trackers = HashMap<String, PartTracker>()

    private val _results = MutableSharedFlow<SmsResult>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val results: SharedFlow<SmsResult> = _results.asSharedFlow()

    override fun availability(): SmsAvailability {
        val feature = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            PackageManager.FEATURE_TELEPHONY_MESSAGING
        } else {
            PackageManager.FEATURE_TELEPHONY
        }
        if (!appContext.packageManager.hasSystemFeature(feature)) return SmsAvailability.NOT_SUPPORTED
        val granted = ContextCompat.checkSelfPermission(appContext, Manifest.permission.SEND_SMS) ==
            PackageManager.PERMISSION_GRANTED
        return if (granted) SmsAvailability.AVAILABLE else SmsAvailability.PERMISSION_NEEDED
    }

    override fun send(requestId: String, phoneNumber: String, text: String): Boolean {
        if (availability() != SmsAvailability.AVAILABLE) return false
        return try {
            val manager = smsManager() ?: return false
            val parts = manager.divideMessage(text)
            synchronized(trackers) { trackers[requestId] = PartTracker(parts.size) }
            val sentIntents = ArrayList(parts.indices.map { pendingIntent(SmsResultReceiver.ACTION_SENT, requestId, mutable = false) })
            // The delivery report's PDU is filled in by the system, so this PendingIntent must be mutable; it is
            // explicit (our own non-exported receiver), so no other app can redirect it.
            val deliveryIntents = ArrayList(parts.indices.map { pendingIntent(SmsResultReceiver.ACTION_DELIVERED, requestId, mutable = true) })
            manager.sendMultipartTextMessage(phoneNumber, null, parts, sentIntents, deliveryIntents)
            true
        } catch (e: SecurityException) {
            forget(requestId)
            false
        } catch (e: IllegalArgumentException) {
            forget(requestId)
            false
        } catch (e: RuntimeException) {
            // Some devices throw when there is no default SMS subscription (no SIM).
            forget(requestId)
            false
        }
    }

    /** Called by [SmsResultReceiver] for every part. */
    fun onPartResult(action: String, requestId: String, resultCode: Int, intent: Intent) {
        val outcome: SmsOutcome? = synchronized(trackers) {
            val tracker = trackers[requestId] ?: return
            when (action) {
                SmsResultReceiver.ACTION_SENT -> {
                    if (resultCode == Activity.RESULT_OK) tracker.sent++ else tracker.failed = true
                    when {
                        tracker.failed && !tracker.reportedSent -> {
                            tracker.reportedSent = true
                            SmsOutcome.FAILED
                        }
                        !tracker.reportedSent && tracker.sent == tracker.total -> {
                            tracker.reportedSent = true
                            SmsOutcome.SENT
                        }
                        else -> null
                    }
                }
                SmsResultReceiver.ACTION_DELIVERED -> {
                    if (isDelivered(intent)) tracker.delivered++
                    if (tracker.delivered == tracker.total) {
                        trackers.remove(requestId)
                        SmsOutcome.DELIVERED
                    } else {
                        null
                    }
                }
                else -> null
            }
        }
        if (outcome != null) _results.tryEmit(SmsResult(requestId, outcome))
    }

    /** GSM status report: TP-Status 0x00..0x1F means "short message received by the recipient". */
    private fun isDelivered(intent: Intent): Boolean {
        val pdu = intent.getByteArrayExtra("pdu") ?: return false
        val format = intent.getStringExtra("format") ?: return false
        return try {
            val status = SmsMessage.createFromPdu(pdu, format)?.status ?: return false
            status in 0x00..0x1F
        } catch (e: RuntimeException) {
            false
        }
    }

    private fun pendingIntent(action: String, requestId: String, mutable: Boolean): PendingIntent {
        val intent = Intent(appContext, SmsResultReceiver::class.java)
            .setAction(action)
            .putExtra(SmsResultReceiver.EXTRA_REQUEST_ID, requestId)
        val mutability = if (mutable && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE
        } else if (mutable) {
            0
        } else {
            PendingIntent.FLAG_IMMUTABLE
        }
        return PendingIntent.getBroadcast(appContext, requestCodes.incrementAndGet(), intent, mutability or PendingIntent.FLAG_ONE_SHOT)
    }

    @Suppress("DEPRECATION")
    private fun smsManager(): SmsManager? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        appContext.getSystemService(SmsManager::class.java)
    } else {
        SmsManager.getDefault()
    }

    private fun forget(requestId: String) = synchronized(trackers) { trackers.remove(requestId) }
}
