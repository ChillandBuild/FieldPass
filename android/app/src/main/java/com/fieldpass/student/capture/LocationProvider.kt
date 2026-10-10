package com.fieldpass.student.capture

import com.fieldpass.student.core.model.LocationFix

/** Source of a FRESH location reading. Never returns a cached last-known fix. */
interface LocationProvider {
    /** Throws [com.fieldpass.student.core.error.AppError] (permission, services off, timeout). */
    suspend fun freshFix(): LocationFix
}
