package com.cybersiren.android.geohash

import android.location.Location

interface LocationProvider {

    fun getLastKnownLocation(callback: (Location?) -> Unit)

    fun requestFreshLocation(callback: (Location?) -> Unit)

    fun requestLocationUpdates(intervalMs: Long, minDistanceMeters: Float, callback: (Location) -> Unit)

    fun removeLocationUpdates(callback: (Location) -> Unit)

    fun cancel()
}
