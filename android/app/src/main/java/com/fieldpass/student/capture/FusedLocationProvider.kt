package com.fieldpass.student.capture

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import com.fieldpass.student.core.error.AppError
import com.fieldpass.student.core.error.ErrorCode
import com.fieldpass.student.core.model.LocationFix
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * One-shot, high-accuracy, current-location request (maxUpdateAge 0 so no cached fix is used).
 * Called only while an attendance attempt is active; there is no background or continuous tracking.
 */
class FusedLocationProvider(private val context: Context) : LocationProvider {

    override suspend fun freshFix(): LocationFix {
        if (!hasPermission()) throw AppError(ErrorCode.LOCATION_PERMISSION_DENIED)
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        if (!LocationManagerCompat.isLocationEnabled(manager)) throw AppError(ErrorCode.LOCATION_SERVICES_OFF)

        val request = CurrentLocationRequest.Builder()
            .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
            .setMaxUpdateAgeMillis(0)
            .setDurationMillis(REQUEST_MILLIS)
            .build()
        val cancel = CancellationTokenSource()
        val reading = try {
            withTimeout(REQUEST_MILLIS + GRACE_MILLIS) {
                LocationServices.getFusedLocationProviderClient(context)
                    .getCurrentLocation(request, cancel.token).await()
            }
        } catch (e: TimeoutCancellationException) {
            cancel.cancel()
            throw AppError(ErrorCode.LOCATION_TIMEOUT, cause = e)
        } catch (e: CancellationException) {
            cancel.cancel()
            throw e
        } catch (e: SecurityException) {
            throw AppError(ErrorCode.LOCATION_PERMISSION_DENIED, cause = e)
        } catch (e: Exception) {
            throw AppError(ErrorCode.LOCATION_TIMEOUT, cause = e)
        }
        val location = reading ?: throw AppError(ErrorCode.LOCATION_TIMEOUT)

        val ageMillis = TimeUnit.NANOSECONDS.toMillis(SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos)
            .coerceAtLeast(0)
        return LocationFix(
            lat = location.latitude,
            lng = location.longitude,
            accuracyM = location.accuracy.toDouble(),
            capturedAt = Instant.ofEpochSecond(Instant.now().minusMillis(ageMillis).epochSecond).toString(),
        )
    }

    private fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private companion object {
        const val REQUEST_MILLIS = 15_000L
        const val GRACE_MILLIS = 3_000L
    }
}
