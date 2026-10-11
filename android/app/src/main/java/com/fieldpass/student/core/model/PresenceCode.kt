package com.fieldpass.student.core.model

/** Shape of the rotating code shown on the faculty screen. The content after the prefix is opaque to the app. */
object PresenceCode {
    const val PREFIX = "fp1."

    /** The server accepts 1..200 characters. */
    const val MAX_LENGTH = 200

    fun isScannable(raw: String): Boolean = raw.startsWith(PREFIX) && raw.length <= MAX_LENGTH
}
