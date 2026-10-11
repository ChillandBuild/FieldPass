package com.fieldpass.student.core.model

/** One fresh location reading taken during an attendance attempt. [capturedAt] is ISO-8601 UTC. */
data class LocationFix(
    val lat: Double,
    val lng: Double,
    val accuracyM: Double,
    val capturedAt: String,
    /** True when Android says this reading came from a mock ("fake GPS") provider. */
    val isMock: Boolean = false,
) {
    val isPoorAccuracy: Boolean get() = accuracyM > POOR_ACCURACY_THRESHOLD_M

    companion object {
        const val POOR_ACCURACY_THRESHOLD_M = 100.0
    }
}

/** Opaque holder for captured JPEG bytes so state classes keep identity equality, not content equality. */
class Selfie(val bytes: ByteArray)
