package com.safesphere.app.core.network

import java.util.concurrent.TimeUnit
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Longer read and write timeouts for photo, video and poster uploads ONLY; every other request keeps the shared client's
 * 15 seconds ([com.safesphere.app.AppContainer.NETWORK_TIMEOUT_SECONDS]).
 *
 * Found on a real phone over the pilot's slow path: a 3 second video took 34 to 37 seconds to be taken in, and the shared
 * 15 second read timeout closed the request while the body was still on its way to the server (the server then saw a
 * request that "ended abruptly" and answered 400). An upload names itself with the marker header below (an annotation
 * on its Retrofit call, so there is still ONE Retrofit and ONE OkHttp client); this interceptor removes the marker
 * (it never leaves the phone) and raises only this call's read and write timeouts. The connect timeout is unchanged:
 * a server that cannot be reached is still reported after 15 seconds.
 */
class UploadTimeoutInterceptor(private val uploadTimeoutSeconds: Long = UPLOAD_TIMEOUT_SECONDS) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.header(HEADER) == null) return chain.proceed(request)
        return chain
            .withReadTimeout(uploadTimeoutSeconds.toInt(), TimeUnit.SECONDS)
            .withWriteTimeout(uploadTimeoutSeconds.toInt(), TimeUnit.SECONDS)
            .proceed(request.newBuilder().removeHeader(HEADER).build())
    }

    companion object {
        /** The marker header name (an app-internal word; removed before the request is sent). */
        const val HEADER = "X-IRL-Upload"

        /** Used in `@Headers(...)` on the upload calls (an annotation needs a constant). */
        const val HEADER_LINE = "$HEADER: long"

        const val UPLOAD_TIMEOUT_SECONDS = 120L
    }
}
