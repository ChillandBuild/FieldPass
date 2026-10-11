package com.fieldpass.student.capture

import com.fieldpass.student.core.model.PresenceCode

/**
 * Decides which scanned strings are worth sending. The scanner fires many times per second for the
 * same code, so each accepted string is handed out once and never again. One instance per attempt.
 */
class PresenceCodeDebounce {
    private val handedOut = HashSet<String>()
    private val retriesLeft = HashMap<String, Int>()

    /** The string to send, or null for anything that is not a FieldPass code or was already handed out. */
    fun next(raw: String): String? = raw.takeIf { PresenceCode.isScannable(it) && handedOut.add(it) }

    /**
     * The send never got an answer, so the same string may go out again while it is still on the screen.
     * Capped, because the scanner would otherwise resend it on every frame while the network is down.
     */
    fun sendFailed(code: String) {
        val left = retriesLeft.getOrPut(code) { MAX_RETRIES }
        if (left == 0) return
        retriesLeft[code] = left - 1
        handedOut.remove(code)
    }

    companion object {
        const val MAX_RETRIES = 2
    }
}
