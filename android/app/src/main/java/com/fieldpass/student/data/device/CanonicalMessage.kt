package com.fieldpass.student.data.device

import com.fieldpass.student.core.model.LocationFix
import java.security.MessageDigest
import java.time.Instant
import java.util.Locale

/** Number text used BOTH in the submit form and in the signed message, so the two can never differ. */
object SubmitFormat {
    fun coordinate(value: Double): String = String.format(Locale.US, "%.6f", value)
    fun accuracy(value: Double): String = String.format(Locale.US, "%.1f", value)
    fun flag(value: Boolean): String = if (value) "1" else "0"
}

private const val MESSAGE_VERSION = "fieldpass-attempt-v1"

private fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(Locale.US, it) }

/** The 12 lines the phone signs for a submit (contracts/API.md, "Phone lock, head turn and phone flags"). */
fun canonicalMessage(
    attemptId: String,
    nonce: String,
    selfie: ByteArray,
    turnPhoto: ByteArray,
    fix: LocationFix,
    rooted: Boolean,
    emulator: Boolean,
): ByteArray = listOf(
    MESSAGE_VERSION, attemptId, nonce, sha256Hex(selfie), sha256Hex(turnPhoto),
    SubmitFormat.coordinate(fix.lat), SubmitFormat.coordinate(fix.lng), SubmitFormat.accuracy(fix.accuracyM),
    Instant.parse(fix.capturedAt).epochSecond.toString(),
    SubmitFormat.flag(fix.isMock), SubmitFormat.flag(rooted), SubmitFormat.flag(emulator),
).joinToString("\n").toByteArray(Charsets.UTF_8)
