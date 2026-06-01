package com.cybersiren.android.geohash

import android.content.Context
import android.location.Geocoder

object GeocoderFactory {
    fun get(context: Context): GeocoderProvider {

        return if (Geocoder.isPresent()) {
            AndroidGeocoderProvider(context)
        } else {
            OpenStreetMapGeocoderProvider()
        }
    }
}
