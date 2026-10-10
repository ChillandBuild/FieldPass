package com.fieldpass.student.data

import com.fieldpass.student.SoftwareDeviceSigner
import com.fieldpass.student.core.model.LocationFix
import com.fieldpass.student.data.device.SubmitFormat
import com.fieldpass.student.data.device.canonicalMessage
import java.security.Signature
import java.util.Base64
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CanonicalMessageTest {
    private val fix = LocationFix(12.9716, 77.5946, 10.0, "2026-10-10T09:31:05Z", isMock = false)

    private fun vector() = canonicalMessage(
        attemptId = "att_1", nonce = "n0nce", selfie = "A".toByteArray(), turnPhoto = "B".toByteArray(),
        fix = fix, rooted = true, emulator = false,
    )

    private val expected = listOf(
        "fieldpass-attempt-v1", "att_1", "n0nce",
        "559aead08264d5795d3909718cdd05abd49572e84fe55590eef31a88a08fdffd",
        "df7e70e5021544f4834bbee64a9e3789febc4be81470df629cad6ddb03320a5c",
        "12.971600", "77.594600", "10.0", "1791624665", "0", "1", "0",
    ).joinToString("\n")

    @Test fun `matches the shared vector byte for byte`() {
        assertEquals(expected, String(vector(), Charsets.UTF_8))
    }

    @Test fun `a comma-decimal phone locale does not change the message`() {
        val before = Locale.getDefault()
        Locale.setDefault(Locale.GERMANY)
        try {
            assertEquals(expected, String(vector(), Charsets.UTF_8))
            assertEquals("12.971600", SubmitFormat.coordinate(12.9716))
            assertEquals("10.0", SubmitFormat.accuracy(10.0))
        } finally {
            Locale.setDefault(before)
        }
    }

    @Test fun `mock flag is the tenth line`() {
        val mocked = canonicalMessage("att_1", "n0nce", "A".toByteArray(), "B".toByteArray(), fix.copy(isMock = true), true, false)
        assertEquals("1", String(mocked, Charsets.UTF_8).split("\n")[9])
    }

    @Test fun `software signer makes a DER signature its own public key verifies`() {
        val signer = SoftwareDeviceSigner()
        val signature = Base64.getDecoder().decode(signer.sign(vector()))
        val verifier = Signature.getInstance("SHA256withECDSA").apply { initVerify(signer.publicKey); update(vector()) }
        assertTrue(verifier.verify(signature))
        assertEquals(signer.publicKeyBase64(), Base64.getEncoder().encodeToString(signer.publicKey.encoded))
    }
}
