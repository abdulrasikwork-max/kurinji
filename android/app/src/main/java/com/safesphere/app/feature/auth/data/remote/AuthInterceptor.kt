package com.safesphere.app.feature.auth.data.remote

import com.safesphere.app.core.network.BackendOrigin
import com.safesphere.app.feature.auth.domain.SessionStorage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Adds "Authorization: Bearer <token>" to every request that needs it, in ONE place.
 * It skips the public auth paths (register and login): those are public, and the server would reject an
 * old or expired token even on a public URL.
 * Phase 17J: and it adds the token ONLY when the request is for the backend's own origin ([backendUrl]: same scheme,
 * host and port); any other address is sent without it.
 * runBlocking is fine here: OkHttp calls interceptors on its own background threads, not the UI thread.
 */
class AuthInterceptor(
    private val sessionStorage: SessionStorage,
    private val backendUrl: HttpUrl
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!BackendOrigin.isSame(backendUrl, request.url)) {
            return chain.proceed(request)
        }
        if (request.url.encodedPath.startsWith(PUBLIC_PATH_PREFIX)) {
            return chain.proceed(request)
        }
        // An empty token is a session whose sealed token could not be opened (sign in to sync): nothing to send.
        val token = runBlocking { sessionStorage.session.first()?.token }
            ?.takeIf { it.isNotEmpty() }
            ?: return chain.proceed(request)

        return chain.proceed(request.newBuilder().header("Authorization", "Bearer $token").build())
    }

    private companion object {
        const val PUBLIC_PATH_PREFIX = "/api/auth/"
    }
}
