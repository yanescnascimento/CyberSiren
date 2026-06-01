package com.cybersiren.android.v2v.auto

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.cybersiren.android.R

object AndroidAutoNotificationHelper {

    private const val CHANNEL_ID = "bitchat_android_auto"
    private const val NOTIFICATION_ID = 996

    fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = context.getString(R.string.android_auto_channel_name)
            val description = context.getString(R.string.android_auto_channel_description)
            val importance = NotificationManager.IMPORTANCE_HIGH

            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                this.description = description
                enableVibration(true)
                setShowBadge(true)
            }

            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    fun showConnectedNotification(context: Context) {
        createNotificationChannel(context)

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setPackage("com.google.android.projection.gearhead")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(context.getString(R.string.android_auto_notification_title))
            .setContentText(context.getString(R.string.android_auto_notification_text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {

        }
    }

    fun cancelNotification(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }
}
