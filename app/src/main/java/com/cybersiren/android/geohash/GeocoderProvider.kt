package com.cybersiren.android.geohash

import android.location.Address

interface GeocoderProvider {

    suspend fun getFromLocation(latitude: Double, longitude: Double, maxResults: Int): List<Address>
}
