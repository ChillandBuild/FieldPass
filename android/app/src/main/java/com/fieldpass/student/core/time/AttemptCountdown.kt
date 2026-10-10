package com.fieldpass.student.core.time

/**
 * Countdown to an attempt's expiry. The total window is the DIFFERENCE between the server's
 * `created_at` and `expires_at`, and elapsed time comes from a monotonic clock, so the device's
 * absolute wall clock is never trusted.
 */
class AttemptCountdown private constructor(
    private val totalMillis: Long,
    private val startedAtElapsed: Long,
) {
    fun remainingMillis(nowElapsed: Long): Long =
        (totalMillis - (nowElapsed - startedAtElapsed)).coerceAtLeast(0)

    fun isExpired(nowElapsed: Long): Boolean = remainingMillis(nowElapsed) <= 0

    companion object {
        /** Null when the server timestamps cannot be parsed or are not increasing. */
        fun fromServerTimes(createdAt: String?, expiresAt: String?, nowElapsed: Long): AttemptCountdown? {
            val total = TimeFormat.millisBetween(createdAt, expiresAt) ?: return null
            if (total <= 0) return null
            return AttemptCountdown(total, nowElapsed)
        }
    }
}
