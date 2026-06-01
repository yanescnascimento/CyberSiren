package com.cybersiren.android.v2v.auto

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.car.app.CarContext
import androidx.car.app.notification.CarAppExtender
import androidx.car.app.notification.CarPendingIntent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.cybersiren.android.R
import com.cybersiren.android.v2v.V2VActivity
import com.cybersiren.android.v2v.model.ReceivedAlert
import com.cybersiren.android.v2v.model.VehicleType
import com.cybersiren.android.v2v.ui.localized

object V2VCarNotifier {

    private const val CHANNEL_ID = "v2v_emergency_alerts"
    private const val CHANNEL_NAME = "Alertas de emergência V2V"
    private const val CHANNEL_DESC = "Notifica quando um veículo de emergência próximo é detectado"

    const val ACTION_OPEN_RECEIVER = "com.cybersiren.app.action.OPEN_AA_RECEIVER"

    private val activeNotifIds = mutableMapOf<String, Int>()

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = CHANNEL_DESC
            enableVibration(true)
            setShowBadge(true)
        }
        nm.createNotificationChannel(channel)
    }

    fun notifyAlert(context: Context, alert: ReceivedAlert, alertUser: Boolean = true) {
        ensureChannel(context)

        val peerId = alert.alert.senderPeerId
        val notifId = activeNotifIds.getOrPut(peerId) { peerId.hashCode() and 0x7FFFFFFF }

        val iconRes = iconResFor(alert.alert.vehicleType)
        val accent = accentColorFor(alert.alert.vehicleType)

        val title = context.localized(
            R.string.v2v_notif_title_nearby,
            alert.alert.vehicleType.localizedName(context)
        )
        val body = buildString {
            append(alert.distanceDisplay)
            val dir = directionText(context, alert.relativeDirection)
            if (dir.isNotEmpty()) append(" · ").append(dir)
            val kmh = alert.alert.speedKmh.toInt()
            if (kmh > 0) append(" · ").append(kmh).append(" km/h")
        }

        val lat = alert.alert.latitude
        val lon = alert.alert.longitude
        val carIntent = Intent(CarContext.ACTION_NAVIGATE)
            .setData(Uri.parse("geo:$lat,$lon?q=v2v_alert"))
        val carPending = try {
            CarPendingIntent.getCarApp(context, notifId, carIntent, PendingIntent.FLAG_IMMUTABLE)
        } catch (_: Exception) {
            null
        }

        val phoneIntent = Intent(context, V2VActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val phonePending = PendingIntent.getActivity(
            context,
            notifId + 1,
            phoneIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val nm = NotificationManagerCompat.from(context)
        if (alertUser) nm.cancel(notifId)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(iconRes)
            .setContentTitle(title)
            .setContentText(body)
            .setColor(accent)
            .setColorized(true)
            .setPriority(if (alertUser) NotificationCompat.PRIORITY_MAX else NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setOnlyAlertOnce(!alertUser)
            .setAutoCancel(true)
            .setContentIntent(phonePending)
            .extend(
                CarAppExtender.Builder()
                    .setImportance(NotificationManager.IMPORTANCE_HIGH)
                    .setContentTitle(title)
                    .setContentText(body)
                    .setSmallIcon(iconRes)
                    .setColor(androidx.car.app.model.CarColor.createCustom(accent, accent))
                    .apply { if (carPending != null) setContentIntent(carPending) }
                    .build()
            )
            .build()

        nm.notify(notifId, notification)
    }

    fun cancelAlert(context: Context, senderPeerId: String) {
        val id = activeNotifIds.remove(senderPeerId) ?: return
        NotificationManagerCompat.from(context).cancel(id)
    }

    fun syncWithActive(context: Context, activeAlerts: List<ReceivedAlert>) {
        val activeIds = activeAlerts.map { it.alert.senderPeerId }.toSet()
        val stale = activeNotifIds.keys.filter { it !in activeIds }
        for (peer in stale) cancelAlert(context, peer)
    }

    fun cancelAll(context: Context) {
        val nm = NotificationManagerCompat.from(context)
        activeNotifIds.values.forEach { nm.cancel(it) }
        activeNotifIds.clear()
    }

    private fun iconResFor(type: VehicleType): Int = when (type) {
        VehicleType.AMBULANCE -> R.drawable.ic_car_ambulance
        VehicleType.FIRE_TRUCK -> R.drawable.ic_car_fire
        VehicleType.POLICE_CAR -> R.drawable.ic_car_police
        VehicleType.EMERGENCY -> R.drawable.ic_car_warning
    }

    private fun accentColorFor(type: VehicleType): Int = when (type) {
        VehicleType.AMBULANCE -> 0xFFE53935.toInt()
        VehicleType.FIRE_TRUCK -> 0xFFF4511E.toInt()
        VehicleType.POLICE_CAR -> 0xFF1E88E5.toInt()
        VehicleType.EMERGENCY -> 0xFFFB8C00.toInt()
    }

    private fun directionText(context: Context, direction: String): String = when (direction.lowercase()) {
        "ahead" -> context.localized(R.string.v2v_notif_dir_ahead)
        "behind" -> context.localized(R.string.v2v_notif_dir_behind)
        "left" -> context.localized(R.string.v2v_notif_dir_left)
        "right" -> context.localized(R.string.v2v_notif_dir_right)
        else -> ""
    }
}
