package com.safesphere.app.core.network

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Phase 17J (defence in depth, Phase 14/15 item "AuthInterceptor adds the token to any host"): the sign-in token is
 * attached ONLY to requests for the backend's own origin (same scheme, host and port as the configured base URL). An
 * image or video address that ever pointed somewhere else would therefore never receive the token.
 */
object BackendOrigin {

    /** True only when [url] has the same scheme, host and port as [backend]. */
    fun isSame(backend: HttpUrl, url: HttpUrl): Boolean =
        url.scheme == backend.scheme &&
            url.host.equals(backend.host, ignoreCase = true) &&
            url.port == backend.port

    /** The same check for a text address; an address that does not parse is never the backend. */
    fun isSame(backend: HttpUrl, url: String): Boolean {
        val parsed = url.toHttpUrlOrNull() ?: return false
        return isSame(backend, parsed)
    }
}
