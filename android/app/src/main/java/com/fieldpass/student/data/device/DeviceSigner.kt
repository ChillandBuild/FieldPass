package com.fieldpass.student.data.device

/** This phone's identity: an EC P-256 key pair whose private half never leaves the device. */
interface DeviceSigner {
    /** Base64 (no line breaks) of the DER SubjectPublicKeyInfo. Sent at login. */
    fun publicKeyBase64(): String

    /** Base64 of a DER ECDSA-SHA256 signature over [message]. */
    fun sign(message: ByteArray): String
}
