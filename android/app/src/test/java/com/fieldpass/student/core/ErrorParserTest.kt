package com.fieldpass.student.core

import com.fieldpass.student.core.error.AppError
import com.fieldpass.student.core.error.ErrorCode
import com.fieldpass.student.core.error.ErrorParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class ErrorParserTest {
    private fun body(code: String) = """{"error": {"code": "$code", "message": "A message."}}"""

    @Test fun everyContractCodeMapsToItsOwnEnum() {
        val contract = mapOf(
            "invalid_credentials" to ErrorCode.INVALID_CREDENTIALS, "not_authenticated" to ErrorCode.NOT_AUTHENTICATED,
            "forbidden" to ErrorCode.FORBIDDEN, "not_assigned" to ErrorCode.NOT_ASSIGNED, "not_found" to ErrorCode.NOT_FOUND,
            "enrollment_not_approved" to ErrorCode.ENROLLMENT_NOT_APPROVED, "outside_activity_window" to ErrorCode.OUTSIDE_ACTIVITY_WINDOW,
            "invalid_action_order" to ErrorCode.INVALID_ACTION_ORDER, "checkpoint_not_open" to ErrorCode.CHECKPOINT_NOT_OPEN,
            "already_decided" to ErrorCode.ALREADY_DECIDED, "attempt_expired" to ErrorCode.ATTEMPT_EXPIRED,
            "image_too_large" to ErrorCode.IMAGE_TOO_LARGE, "validation_error" to ErrorCode.VALIDATION_ERROR,
            "unusable_capture" to ErrorCode.UNUSABLE_CAPTURE, "multiple_faces" to ErrorCode.MULTIPLE_FACES,
            "location_stale" to ErrorCode.LOCATION_STALE,
        )
        for ((wire, expected) in contract) assertEquals(wire, expected, ErrorParser.fromHttp(400, body(wire)).code)
    }

    @Test fun `phone lock codes map to their own errors`() {
        assertEquals(ErrorCode.DEVICE_MISMATCH, ErrorCode.fromWire("device_mismatch"))
        assertEquals(ErrorCode.DEVICE_TAKEN, ErrorCode.fromWire("device_taken"))
        assertEquals(ErrorCode.DEVICE_KEY_REQUIRED, ErrorCode.fromWire("device_key_required"))
        assertEquals(ErrorCode.BAD_SIGNATURE, ErrorCode.fromWire("bad_signature"))
    }

    @Test fun carriesHttpStatusAndServerMessage() {
        val e = ErrorParser.fromHttp(410, body("attempt_expired"))
        assertEquals(410, e.httpStatus)
        assertEquals("A message.", e.serverMessage)
    }

    @Test fun unknownCodeBecomesUnknown() {
        assertEquals(ErrorCode.UNKNOWN, ErrorParser.fromHttp(418, body("teapot")).code)
    }

    @Test fun nonJsonBodyFallsBack() {
        assertEquals(ErrorCode.SERVER_ERROR, ErrorParser.fromHttp(502, "<html>bad gateway</html>").code)
        assertEquals(ErrorCode.NOT_AUTHENTICATED, ErrorParser.fromHttp(401, null).code)
        assertEquals(ErrorCode.SERVER_ERROR, ErrorParser.fromHttp(500, "").code)
    }

    @Test fun ioFailuresAreLostResponses() {
        assertEquals(ErrorCode.TIMEOUT, ErrorParser.fromThrowable(SocketTimeoutException()).code)
        assertEquals(ErrorCode.NETWORK, ErrorParser.fromThrowable(UnknownHostException()).code)
        assertTrue(ErrorParser.fromThrowable(IOException("boom")).isLostResponse)
        assertFalse(ErrorParser.fromHttp(422, body("unusable_capture")).isLostResponse)
    }

    @Test fun appErrorPassesThroughAndSessionExpiryIsFlagged() {
        val original = AppError(ErrorCode.NOT_AUTHENTICATED)
        assertTrue(ErrorParser.fromThrowable(original).isSessionExpired)
        assertEquals(ErrorCode.UNKNOWN, ErrorParser.fromThrowable(IllegalStateException()).code)
    }

    @Test fun everyCodeHasAUserFacingString() {
        for (code in ErrorCode.entries) assertNotEquals(code.name, 0, code.messageRes)
    }
}
