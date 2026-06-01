package com.cybersiren.wear.data

import android.util.Log
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.WearableListenerService

class AlertListenerService : WearableListenerService() {

    companion object {
        private const val TAG = "WearAlertListener"
        private const val PATH = "/v2v/alerts"
    }

    override fun onDataChanged(events: DataEventBuffer) {
        for (event in events) {
            if (event.type != DataEvent.TYPE_CHANGED) continue
            if (event.dataItem.uri.path != PATH) continue

            val map = DataMapItem.fromDataItem(event.dataItem).dataMap
            val ids = map.getStringArrayList("ids") ?: arrayListOf()
            val vehicles = map.getStringArrayList("vehicles") ?: arrayListOf()
            val distances = map.getFloatArray("distances") ?: floatArrayOf()
            val directions = map.getStringArrayList("directions") ?: arrayListOf()
            val urgencies = map.getStringArrayList("urgencies") ?: arrayListOf()

            val list = (ids.indices).map { i ->
                WearAlert(
                    id = ids[i],
                    vehicleType = runCatching { WearVehicleType.valueOf(vehicles[i]) }
                        .getOrDefault(WearVehicleType.EMERGENCY),
                    distanceMeters = distances.getOrElse(i) { Float.MAX_VALUE },
                    direction = directions.getOrElse(i) { "" },
                    urgency = runCatching { WearUrgency.valueOf(urgencies[i]) }
                        .getOrDefault(WearUrgency.LOW)
                )
            }

            Log.i(TAG, "Received ${list.size} alerts from phone")
            WearAlertRepository.update(list)
        }
    }
}
