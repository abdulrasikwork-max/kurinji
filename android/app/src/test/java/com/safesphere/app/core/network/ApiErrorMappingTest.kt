package com.safesphere.app.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiErrorMappingTest {

    @Test
    fun `http codes map to app errors`() {
        assertEquals(ApiError.UNAUTHORIZED, apiErrorForHttpCode(401))
        assertEquals(ApiError.NOT_FOUND, apiErrorForHttpCode(404))
        assertEquals(ApiError.CONFLICT, apiErrorForHttpCode(409))
        assertEquals(ApiError.VALIDATION, apiErrorForHttpCode(400))
        assertEquals(ApiError.VALIDATION, apiErrorForHttpCode(422))
        assertEquals(ApiError.SERVER, apiErrorForHttpCode(500))
        assertEquals(ApiError.SERVER, apiErrorForHttpCode(503))
        assertEquals(ApiError.UNKNOWN, apiErrorForHttpCode(418))
        assertEquals(ApiError.TOO_MANY_ATTEMPTS, apiErrorForHttpCode(429))
    }

    @Test
    fun `only an unauthorized failure counts as an expired session`() {
        assertTrue(ApiResult.Failure(ApiError.UNAUTHORIZED).isSessionExpired())
        assertFalse(ApiResult.Failure(ApiError.NO_CONNECTION).isSessionExpired())
        assertFalse(ApiResult.Success(1).isSessionExpired())
    }
}
