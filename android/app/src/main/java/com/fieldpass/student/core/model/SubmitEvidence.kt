package com.fieldpass.student.core.model

/** What the phone reports about itself. The server only flags these for faculty; it never blocks on them. */
data class DeviceFlags(val rooted: Boolean, val emulator: Boolean)

/** Everything one submit carries. A plain class: JPEG bytes keep identity equality, like [Selfie]. */
class SubmitEvidence(
    val selfie: ByteArray,
    val turnPhoto: ByteArray,
    val fix: LocationFix,
    val flags: DeviceFlags,
)
