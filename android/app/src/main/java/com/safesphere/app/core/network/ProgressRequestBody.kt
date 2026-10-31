package com.safesphere.app.core.network

import okhttp3.MediaType
import okhttp3.RequestBody
import okio.Buffer
import okio.BufferedSink
import okio.ForwardingSink
import okio.Sink
import okio.buffer

/**
 * Phase 17C: a request body that reports how much of itself has been written to the connection (0..1). Each write
 * starts again from 0, because ApiCallHandler may send a call once more after renewing the token; the share never
 * climbs past 1. [onProgress] runs on OkHttp's thread and is called only when the whole percent changes. The wrapped
 * body must be one that can be written more than once (a file or a byte array, not a one-shot stream).
 */
class ProgressRequestBody(
    private val delegate: RequestBody,
    private val onProgress: (Float) -> Unit
) : RequestBody() {

    override fun contentType(): MediaType? = delegate.contentType()

    override fun contentLength(): Long = delegate.contentLength()

    override fun isOneShot(): Boolean = false

    override fun writeTo(sink: BufferedSink) {
        val total = contentLength()
        var lastPercent = -1
        val counting = object : ForwardingSink(sink) {
            var written = 0L

            override fun write(source: Buffer, byteCount: Long) {
                super.write(source, byteCount)
                written += byteCount
                if (total > 0) {
                    val percent = ((written * 100) / total).toInt().coerceIn(0, 100)
                    if (percent != lastPercent) {
                        lastPercent = percent
                        onProgress(percent / 100f)
                    }
                }
            }
        }
        onProgress(0f)
        val buffered = (counting as Sink).buffer()
        delegate.writeTo(buffered)
        buffered.flush()
    }
}
