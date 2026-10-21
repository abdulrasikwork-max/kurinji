package com.safesphere.app.feature.auth.data.remote

import com.safesphere.app.feature.auth.domain.SessionStorage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Adds "Authorization: Bearer <token>" to every request that needs it, in ONE place.
 * It skips the /api/auth/ paths (register and login): those are public, and the server would reject an
 * old or expired token even on a public URL.
 * runBlocking is fine here: OkHttp calls interceptors on its own background threads, not the UI thread.
 */
class AuthInterceptor(private val sessionStorage: SessionStorage) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.url.encodedPath.startsWith(PUBLIC_PATH_PREFIX)) {
            return chain.proceed(request)
        }
        val token = runBlocking { sessionStorage.session.first()?.token }
            ?: return chain.proceed(request)

        return chain.proceed(request.newBuilder().header("Authorization", "Bearer $token").build())
    }

    private companion object {
        const val PUBLIC_PATH_PREFIX = "/api/auth/"
    }
}
