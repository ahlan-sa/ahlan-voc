package com.fbint.collector.data

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Wraps Google Play Services FusedLocationProviderClient. Requires runtime permission — if the
 * surveyor never granted ACCESS_COARSE_LOCATION (or finer), [current] returns null and
 * submission remains blocked with the answers saved as a draft.
 */
@Singleton
class LocationProvider @Inject constructor(
    @ApplicationContext private val ctx: Context,
) {
    private val client by lazy { LocationServices.getFusedLocationProviderClient(ctx) }

    fun hasPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION)
        return fine == PackageManager.PERMISSION_GRANTED || coarse == PackageManager.PERMISSION_GRANTED
    }

    suspend fun current(timeoutMs: Long = 4_000): Location? = request(timeoutMs, 15_000)

    /** Called only while the collecting screen is in the foreground. */
    suspend fun warm(): Location? = request(10_000, 5_000)

    @SuppressLint("MissingPermission")
    private suspend fun request(timeoutMs: Long, maxAgeMs: Long): Location? {
        if (!hasPermission()) return null
        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<Location?> { cont ->
                val token = CancellationTokenSource()
                cont.invokeOnCancellation { token.cancel() }
                val request = CurrentLocationRequest.Builder()
                    .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
                    .setMaxUpdateAgeMillis(maxAgeMs)
                    .setDurationMillis(timeoutMs)
                    .build()
                client.getCurrentLocation(request, token.token)
                    .addOnSuccessListener { loc -> if (cont.isActive) cont.resume(loc) }
                    .addOnFailureListener { if (cont.isActive) cont.resume(null) }
                    .addOnCanceledListener { if (cont.isActive) cont.resume(null) }
            }
        }
    }
}
