package app.polybot.data.api

import app.polybot.core.result.ApiError
import app.polybot.data.api.dto.ApiJson
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class ErrorMapperTest {
    private fun map(status: Int, body: String, headers: Map<String, String> = emptyMap()) =
        ErrorMapper.map(status, { headers[it] }, body, ApiJson)

    @Test
    fun `gateway unauthenticated is permanent auth error`() {
        val e = map(401, """{"code":"UNAUTHENTICATED"}""")
        assertInstanceOf(ApiError.Auth::class.java, e)
        assertEquals("UNAUTHENTICATED", (e as ApiError.Auth).code)
    }

    @Test
    fun `problem json is parsed`() {
        val e = map(
            401,
            """{"type":"https://polyester.dev/problems/unauthenticated","title":"Unauthenticated","status":401,"detail":"Authentication required.","code":"UNAUTHENTICATED"}""",
        )
        assertEquals("Authentication required.", e.message)
    }

    @Test
    fun `timestamp skew is retryable clock error even with 401`() {
        assertInstanceOf(ApiError.ClockSkew::class.java, map(401, """{"code":"TIMESTAMP_SKEW"}"""))
    }

    @Test
    fun `permanent key errors`() {
        for (code in listOf("SIGNATURE_INVALID", "KEY_DISABLED", "IP_NOT_ALLOWED")) {
            assertInstanceOf(ApiError.Auth::class.java, map(401, """{"code":"$code"}"""), code)
        }
        assertInstanceOf(ApiError.Auth::class.java, map(403, """{"code":"permission_denied","message":"no"}"""))
    }

    @Test
    fun `auth temporarily unavailable is transient`() {
        assertInstanceOf(ApiError.Transient::class.java, map(401, """{"code":"AUTH_TEMPORARILY_UNAVAILABLE"}"""))
    }

    @Test
    fun `outcome unknown suffix is matched and never retryable`() {
        val e = map(500, """{"code":"ERROR_CODE_ORDER_CREATE_OUTCOME_UNKNOWN","message":"x"}""")
        assertInstanceOf(ApiError.OutcomeUnknown::class.java, e)
        assertEquals(false, e is ApiError.Retryable)
    }

    @Test
    fun `rate limit prefers detail then headers then default`() {
        val withDetail = map(
            429,
            """{"code":"resource_exhausted","details":[{"type":"polyester.ratelimit.v1.RateLimitDetail","debug":{"retryAfterMs":"2500"}}]}""",
            mapOf("Retry-After-Ms" to "999"),
        ) as ApiError.RateLimited
        assertEquals(2500, withDetail.retryAfterMs)

        assertEquals(999, (map(429, "{}", mapOf("Retry-After-Ms" to "999")) as ApiError.RateLimited).retryAfterMs)
        assertEquals(3000, (map(429, "{}", mapOf("Retry-After" to "3")) as ApiError.RateLimited).retryAfterMs)
        assertEquals(1000, (map(429, "not json") as ApiError.RateLimited).retryAfterMs)
    }

    @Test
    fun `not available and timeouts and server errors`() {
        assertInstanceOf(ApiError.NotAvailable::class.java, map(404, "{}"))
        assertInstanceOf(ApiError.NotAvailable::class.java, map(501, """{"code":"unimplemented"}"""))
        assertInstanceOf(ApiError.Timeout::class.java, map(504, "{}"))
        assertInstanceOf(ApiError.Timeout::class.java, map(200, """{"code":"deadline_exceeded"}"""))
        assertInstanceOf(ApiError.Transient::class.java, map(503, "<html>bad gateway</html>"))
    }

    @Test
    fun `duplicate client order id carries the existing order`() {
        val snake = map(409, """{"code":"already_exists","message":"CONFLICT_DUPLICATE_CLIENT_ORDER_ID","existing_order_id":"123"}""")
        assertEquals(123L, (snake as ApiError.DuplicateClientOrderId).existingOrderId)
        val camel = map(409, """{"code":"CONFLICT_DUPLICATE_CLIENT_ORDER_ID","existingOrderId":456}""")
        assertEquals(456L, (camel as ApiError.DuplicateClientOrderId).existingOrderId)
        assertEquals(null, (map(409, """{"code":"CONFLICT_DUPLICATE_CLIENT_ORDER_ID"}""") as ApiError.DuplicateClientOrderId).existingOrderId)
    }

    @Test
    fun `missing object is not found but missing route is not available`() {
        assertInstanceOf(ApiError.NotFound::class.java, map(404, """{"code":"not_found","message":"order"}"""))
        assertInstanceOf(ApiError.NotFound::class.java, map(404, """{"code":"order_not_found"}"""))
        assertInstanceOf(ApiError.NotAvailable::class.java, map(404, """{"code":"route_not_found"}"""))
        assertInstanceOf(ApiError.NotAvailable::class.java, map(404, "{}"))
    }

    @Test
    fun `validation errors are permanent`() {
        val e = map(400, """{"code":"invalid_argument","message":"validation error: timeframe: must not be in list [0]"}""")
        assertInstanceOf(ApiError.Validation::class.java, e)
        assertEquals("invalid_argument", (e as ApiError.Validation).code)
    }

    @Test
    fun `duplicate client order id is recognised by message or code variants`() {
        assertInstanceOf(ApiError.DuplicateClientOrderId::class.java, map(409, """{"code":"already_exists","message":"Duplicate client order ID."}"""))
        assertInstanceOf(ApiError.DuplicateClientOrderId::class.java, map(409, """{"code":"duplicate_client_order_id"}"""))
    }

    @Test
    fun `validation message carries the server code`() {
        assertEquals(true, map(400, """{"code":"invalid_argument","message":"bad"}""").message!!.contains("invalid_argument"))
    }
}
