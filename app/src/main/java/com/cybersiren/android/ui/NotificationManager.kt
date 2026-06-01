package com.cybersiren.android.ui

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.app.NotificationManagerCompat
import com.cybersiren.android.MainActivity
import com.cybersiren.android.R
import com.cybersiren.android.util.NotificationIntervalManager
import java.util.concurrent.ConcurrentHashMap

class NotificationManager(
  private val context: Context,
  private val notificationManager: NotificationManagerCompat,
  private val notificationIntervalManager: NotificationIntervalManager
) {

    companion object {
        private const val TAG = "NotificationManager"
        private const val CHANNEL_ID = "bitchat_dm_notifications"
        private const val GEOHASH_CHANNEL_ID = "bitchat_geohash_notifications"
        private const val GROUP_KEY_DM = "bitchat_dm_group"
        private const val GROUP_KEY_GEOHASH = "bitchat_geohash_group"
        private const val NOTIFICATION_REQUEST_CODE = 1000
        private const val GEOHASH_NOTIFICATION_REQUEST_CODE = 2000
        private const val SUMMARY_NOTIFICATION_ID = 999
      private const val GEOHASH_SUMMARY_NOTIFICATION_ID = 998
        private const val ACTIVE_PEERS_NOTIFICATION_ID = 997
        private const val ACTIVE_PEERS_NOTIFICATION_TIME_INTERVAL = com.cybersiren.android.util.AppConstants.UI.ACTIVE_PEERS_NOTIFICATION_INTERVAL_MS

        const val EXTRA_OPEN_PRIVATE_CHAT = "open_private_chat"
        const val EXTRA_OPEN_GEOHASH_CHAT = "open_geohash_chat"
        const val EXTRA_PEER_ID = "peer_id"
        const val EXTRA_SENDER_NICKNAME = "sender_nickname"
        const val EXTRA_GEOHASH = "geohash"
    }

    private val systemNotificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private val pendingNotifications = ConcurrentHashMap<String, MutableList<PendingNotification>>()
    private val pendingGeohashNotifications = ConcurrentHashMap<String, MutableList<GeohashNotification>>()

    @Volatile
    private var isAppInBackground = false

    @Volatile
    private var currentPrivateChatPeer: String? = null

    @Volatile
    private var currentGeohash: String? = null

    data class PendingNotification(
        val senderPeerID: String,
        val senderNickname: String,
        val messageContent: String,
        val timestamp: Long
    )

    data class GeohashNotification(
        val geohash: String,
        val senderNickname: String,
        val messageContent: String,
        val timestamp: Long,
        val isMention: Boolean,
        val isFirstMessage: Boolean,
        val locationName: String? = null
    )

    init {
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

            val dmName = "Direct Messages"
            val dmDescriptionText = "Notifications for private messages from other users"
            val dmImportance = NotificationManager.IMPORTANCE_HIGH
            val dmChannel = NotificationChannel(CHANNEL_ID, dmName, dmImportance).apply {
                description = dmDescriptionText
                enableVibration(true)
                setShowBadge(true)
            }
            systemNotificationManager.createNotificationChannel(dmChannel)

            val geohashName = "Geohash Chats"
            val geohashDescriptionText = "Notifications for mentions and messages in geohash location channels"
            val geohashImportance = NotificationManager.IMPORTANCE_HIGH
            val geohashChannel = NotificationChannel(GEOHASH_CHANNEL_ID, geohashName, geohashImportance).apply {
                description = geohashDescriptionText
                enableVibration(true)
                setShowBadge(true)
            }
            systemNotificationManager.createNotificationChannel(geohashChannel)
        }
    }

    fun setAppBackgroundState(inBackground: Boolean) {
        isAppInBackground = inBackground
        Log.d(TAG, "App background state changed: $inBackground")
    }

    fun setCurrentPrivateChatPeer(peerID: String?) {
        currentPrivateChatPeer = peerID
        Log.d(TAG, "Current private chat peer changed: $peerID")
    }

    fun setCurrentGeohash(geohash: String?) {
        currentGeohash = geohash
        Log.d(TAG, "Current geohash changed: $geohash")
    }

    fun showPrivateMessageNotification(senderPeerID: String, senderNickname: String, messageContent: String) {

        val shouldNotify = isAppInBackground || (!isAppInBackground && currentPrivateChatPeer != senderPeerID)

        if (!shouldNotify) {
            Log.d(TAG, "Skipping notification - app in foreground and viewing chat with $senderNickname")
            return
        }

        Log.d(TAG, "Showing notification for message from $senderNickname (peerID: $senderPeerID)")

        val notification = PendingNotification(
            senderPeerID = senderPeerID,
            senderNickname = senderNickname,
            messageContent = messageContent,
            timestamp = System.currentTimeMillis()
        )

        pendingNotifications.computeIfAbsent(senderPeerID) { mutableListOf() }.add(notification)

        showNotificationForSender(senderPeerID)

        if (pendingNotifications.size > 1) {
            showSummaryNotification()
        }
    }

    fun showActiveUserNotification(peers: List<String>) {
        val currentTime = System.currentTimeMillis()
        val activePeerNotificationIntervalExceeded =
          (currentTime - notificationIntervalManager.lastNetworkNotificationTime) > ACTIVE_PEERS_NOTIFICATION_TIME_INTERVAL
        val newPeers = peers - notificationIntervalManager.recentlySeenPeers
        if (isAppInBackground && activePeerNotificationIntervalExceeded && newPeers.isNotEmpty()) {
            Log.d(TAG, "Showing notification for active peers")
            showNotificationForActivePeers(peers.size)
            notificationIntervalManager.setLastNetworkNotificationTime(currentTime)
            notificationIntervalManager.recentlySeenPeers.addAll(newPeers)
        } else {
            Log.d(TAG, "Skipping notification - app in foreground or it has been less than 5 minutes since last active peer notification")
            return
        }
    }

    private fun showNotificationForSender(senderPeerID: String) {
        val notifications = pendingNotifications[senderPeerID] ?: return
        if (notifications.isEmpty()) return

        val latestNotification = notifications.last()
        val messageCount = notifications.size

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_OPEN_PRIVATE_CHAT, true)
            putExtra(EXTRA_PEER_ID, senderPeerID)
            putExtra(EXTRA_SENDER_NICKNAME, latestNotification.senderNickname)
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            NOTIFICATION_REQUEST_CODE + senderPeerID.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val person = Person.Builder()
            .setName(latestNotification.senderNickname)
            .setKey(senderPeerID)
            .build()

        val contentText = if (messageCount == 1) {
            latestNotification.messageContent
        } else {
            "${latestNotification.messageContent} (+${messageCount - 1} more)"
        }

        val contentTitle = if (messageCount == 1) {
            latestNotification.senderNickname
        } else {
            "${latestNotification.senderNickname} ($messageCount messages)"
        }

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(contentTitle)
            .setContentText(contentText)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .addPerson(person)
            .setShowWhen(true)
            .setWhen(latestNotification.timestamp)

        if (pendingNotifications.size > 1) {
            builder.setGroup(GROUP_KEY_DM)
        }

        if (messageCount > 1) {
            val style = NotificationCompat.InboxStyle()
                .setBigContentTitle(contentTitle)

            notifications.takeLast(5).forEach { notif ->
                style.addLine(notif.messageContent)
            }

            if (messageCount > 5) {
                val extra = messageCount - 5
                style.setSummaryText(context.resources.getQuantityString(
                    R.plurals.notification_and_more, extra, extra
                ))
            }

            builder.setStyle(style)
        } else {

            builder.setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(latestNotification.messageContent)
            )
        }

        val notificationId = senderPeerID.hashCode()
        notificationManager.notify(notificationId, builder.build())

        Log.d(TAG, "Displayed notification for $contentTitle with ID $notificationId")
    }

    fun showVerificationNotification(title: String, body: String, peerID: String? = null) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (peerID != null) {
                putExtra(EXTRA_OPEN_PRIVATE_CHAT, true)
                putExtra(EXTRA_PEER_ID, peerID)
                putExtra(EXTRA_SENDER_NICKNAME, body)
            }
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            (System.currentTimeMillis() and 0x7FFFFFFF).toInt(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setShowWhen(true)
            .setWhen(System.currentTimeMillis())

        notificationManager.notify((System.currentTimeMillis() and 0x7FFFFFFF).toInt(), builder.build())
    }

    private fun showNotificationForActivePeers(peersSize: Int) {

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val pendingIntent = PendingIntent.getActivity(
          context,
          ACTIVE_PEERS_NOTIFICATION_ID,
          intent,
          PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val contentTitle = context.getString(R.string.notification_active_peers_title)
        val contentText = if (peersSize == 1) {
            context.getString(R.string.notification_active_peers_one)
        } else {
            context.getString(R.string.notification_active_peers_many, peersSize)
        }

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
          .setSmallIcon(R.drawable.ic_notification)
          .setContentTitle(contentTitle)
          .setContentText(contentText)
          .setContentIntent(pendingIntent)
          .setAutoCancel(true)
          .setPriority(NotificationCompat.PRIORITY_MIN)
          .setCategory(NotificationCompat.CATEGORY_MESSAGE)
          .setShowWhen(true)
          .setWhen(System.currentTimeMillis())

        notificationManager.notify(ACTIVE_PEERS_NOTIFICATION_ID, builder.build())
        Log.d(TAG, "Displayed notification for $contentTitle with ID $ACTIVE_PEERS_NOTIFICATION_ID")
    }
    private fun showSummaryNotification() {
        if (pendingNotifications.isEmpty()) return

        val totalMessages = pendingNotifications.values.sumOf { it.size }
        val senderCount = pendingNotifications.size

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            NOTIFICATION_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(context.getString(R.string.notification_messages_from_people, totalMessages, senderCount))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setGroup(GROUP_KEY_DM)
            .setGroupSummary(true)

        val style = NotificationCompat.InboxStyle()
            .setBigContentTitle(context.getString(R.string.notification_new_location_messages))

        pendingNotifications.entries.take(5).forEach { (peerID, notifications) ->
            val latestNotif = notifications.last()
            val count = notifications.size
            val line = if (count == 1) {
                "${latestNotif.senderNickname}: ${latestNotif.messageContent}"
            } else {
                "${latestNotif.senderNickname}: $count messages"
            }
            style.addLine(line)
        }

        if (pendingNotifications.size > 5) {
            style.setSummaryText(context.getString(R.string.notification_more_conversations, pendingNotifications.size - 5))
        }

        builder.setStyle(style)

        notificationManager.notify(SUMMARY_NOTIFICATION_ID, builder.build())

        Log.d(TAG, "Displayed summary notification for $senderCount senders")
    }

    fun clearNotificationsForSender(senderPeerID: String) {
        pendingNotifications.remove(senderPeerID)

        val notificationId = senderPeerID.hashCode()
        notificationManager.cancel(notificationId)

        if (pendingNotifications.isEmpty()) {
            notificationManager.cancel(SUMMARY_NOTIFICATION_ID)
        } else if (pendingNotifications.size == 1) {

            notificationManager.cancel(SUMMARY_NOTIFICATION_ID)
        } else {

            showSummaryNotification()
        }

        Log.d(TAG, "Cleared notifications for sender: $senderPeerID")
    }

    fun showGeohashNotification(
        geohash: String,
        senderNickname: String,
        messageContent: String,
        isMention: Boolean = false,
        isFirstMessage: Boolean = false,
        locationName: String? = null
    ) {

        val shouldNotify = isAppInBackground || (!isAppInBackground && currentGeohash != geohash)

        if (!shouldNotify) {
            Log.d(TAG, "Skipping geohash notification - app in foreground and viewing geohash $geohash")
            return
        }

        Log.d(TAG, "Showing geohash notification for $geohash from $senderNickname (mention: $isMention, first: $isFirstMessage)")

        val notification = GeohashNotification(
            geohash = geohash,
            senderNickname = senderNickname,
            messageContent = messageContent,
            timestamp = System.currentTimeMillis(),
            isMention = isMention,
            isFirstMessage = isFirstMessage,
            locationName = locationName
        )

        pendingGeohashNotifications.computeIfAbsent(geohash) { mutableListOf() }.add(notification)

        showNotificationForGeohash(geohash)

        if (pendingGeohashNotifications.size > 1) {
            showGeohashSummaryNotification()
        }
    }

    private fun showNotificationForGeohash(geohash: String) {
        val notifications = pendingGeohashNotifications[geohash] ?: return
        if (notifications.isEmpty()) return

        val latestNotification = notifications.last()
        val messageCount = notifications.size
        val mentionCount = notifications.count { it.isMention }
        val firstMessageCount = notifications.count { it.isFirstMessage }

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_OPEN_GEOHASH_CHAT, true)
            putExtra(EXTRA_GEOHASH, geohash)
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            GEOHASH_NOTIFICATION_REQUEST_CODE + geohash.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val geohashDisplay = latestNotification.locationName?.let { "$it (#$geohash)" } ?: "#$geohash"
        val contentTitle = when {
            mentionCount > 0 && firstMessageCount > 0 && messageCount > 1 -> context.getString(R.string.notification_mentions_in_more, geohashDisplay, messageCount - 1)
            mentionCount > 0 -> if (mentionCount == 1) context.getString(R.string.notification_mentions_in, geohashDisplay) else context.getString(R.string.notification_mentions_in_plural, mentionCount, geohashDisplay)
            firstMessageCount > 0 -> context.getString(R.string.notification_new_activity_in, geohashDisplay)
            else -> context.getString(R.string.notification_messages_in, geohashDisplay)
        }

        val contentText = when {
            latestNotification.isMention -> "${latestNotification.senderNickname}: ${latestNotification.messageContent}"
            latestNotification.isFirstMessage -> context.getString(R.string.notification_joined_conversation, latestNotification.senderNickname)
            else -> "${latestNotification.senderNickname}: ${latestNotification.messageContent}"
        }

        val builder = NotificationCompat.Builder(context, GEOHASH_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(contentTitle)
            .setContentText(contentText)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(if (latestNotification.isMention) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setShowWhen(true)
            .setWhen(latestNotification.timestamp)

        if (pendingGeohashNotifications.size > 1) {
            builder.setGroup(GROUP_KEY_GEOHASH)
        }

        if (messageCount > 1) {
            val style = NotificationCompat.InboxStyle()
                .setBigContentTitle(contentTitle)

            notifications.takeLast(5).forEach { notif ->
                val prefix = when {
                    notif.isMention -> ""
                    notif.isFirstMessage -> ""
                    else -> ""
                }
                style.addLine("$prefix${notif.senderNickname}: ${notif.messageContent}")
            }

            if (messageCount > 5) {
                val extra = messageCount - 5
                style.setSummaryText(context.resources.getQuantityString(R.plurals.notification_and_more, extra, extra))
            }

            builder.setStyle(style)
        } else {

            builder.setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(contentText)
            )
        }

        val notificationId = 3000 + geohash.hashCode()
        notificationManager.notify(notificationId, builder.build())

        Log.d(TAG, "Displayed geohash notification for $contentTitle with ID $notificationId")
    }

    private fun showGeohashSummaryNotification() {
        if (pendingGeohashNotifications.isEmpty()) return

        val totalMessages = pendingGeohashNotifications.values.sumOf { it.size }
        val geohashCount = pendingGeohashNotifications.size
        val totalMentions = pendingGeohashNotifications.values.sumOf { notifications ->
            notifications.count { it.isMention }
        }

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            GEOHASH_NOTIFICATION_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val contentTitle = if (totalMentions > 0) {
            context.getString(R.string.notification_geohash_summary_title_mentions, totalMentions)
        } else {
            context.getString(R.string.notification_geohash_summary_title)
        }

        val contentText = context.getString(R.string.notification_geohash_summary_text, totalMessages, geohashCount)

        val builder = NotificationCompat.Builder(context, GEOHASH_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(contentTitle)
            .setContentText(contentText)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setGroup(GROUP_KEY_GEOHASH)
            .setGroupSummary(true)

        val style = NotificationCompat.InboxStyle()
            .setBigContentTitle(context.getString(R.string.notification_new_messages))

        pendingGeohashNotifications.entries.take(5).forEach { (geohash, notifications) ->
            val mentionCount = notifications.count { it.isMention }
            val messageCount = notifications.size
            val latestNotification = notifications.last()
            val geohashDisplay = latestNotification.locationName?.let { "$it (#$geohash)" } ?: "#$geohash"
            val line = when {
                mentionCount > 0 -> "$geohashDisplay: $mentionCount mentions (+${messageCount - mentionCount} more)"
                messageCount == 1 -> "$geohashDisplay: 1 message"
                else -> "$geohashDisplay: $messageCount messages"
            }
            style.addLine(line)
        }

        if (pendingGeohashNotifications.size > 5) {
            style.setSummaryText(context.getString(R.string.notification_more_locations, pendingGeohashNotifications.size - 5))
        }

        builder.setStyle(style)

        notificationManager.notify(GEOHASH_SUMMARY_NOTIFICATION_ID, builder.build())

        Log.d(TAG, "Displayed geohash summary notification for $geohashCount locations")
    }

    fun clearNotificationsForGeohash(geohash: String) {
        pendingGeohashNotifications.remove(geohash)

        val notificationId = 3000 + geohash.hashCode()
        notificationManager.cancel(notificationId)

        if (pendingGeohashNotifications.isEmpty()) {
            notificationManager.cancel(GEOHASH_SUMMARY_NOTIFICATION_ID)
        } else if (pendingGeohashNotifications.size == 1) {

            notificationManager.cancel(GEOHASH_SUMMARY_NOTIFICATION_ID)
        } else {

            showGeohashSummaryNotification()
        }

        Log.d(TAG, "Cleared notifications for geohash: $geohash")
    }

    fun showMeshMentionNotification(
        senderNickname: String,
        messageContent: String,
        senderPeerID: String? = null
    ) {

        val isViewingMeshChat = currentPrivateChatPeer == null && currentGeohash == null
        val shouldNotify = isAppInBackground || (!isAppInBackground && !isViewingMeshChat)

        if (!shouldNotify) {
            Log.d(TAG, "Skipping mesh mention notification - app in foreground and viewing mesh chat")
            return
        }

        Log.d(TAG, "Showing mesh mention notification from $senderNickname")

        val meshMentionKey = "mesh_mentions"
        val notification = PendingNotification(
            senderPeerID = senderPeerID ?: meshMentionKey,
            senderNickname = senderNickname,
            messageContent = messageContent,
            timestamp = System.currentTimeMillis()
        )

        pendingNotifications.computeIfAbsent(meshMentionKey) { mutableListOf() }.add(notification)

        showNotificationForMeshMentions()

        if (pendingNotifications.size > 1) {
            showSummaryNotification()
        }
    }

    private fun showNotificationForMeshMentions() {
        val notifications = pendingNotifications["mesh_mentions"] ?: return
        if (notifications.isEmpty()) return

        val latestNotification = notifications.last()
        val messageCount = notifications.size

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP

        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            NOTIFICATION_REQUEST_CODE + "mesh_mentions".hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val contentTitle = if (messageCount == 1) {
            context.getString(R.string.notification_mesh_mention_title_singular)
        } else {
            context.getString(R.string.notification_mesh_mention_title_plural, messageCount)
        }

        val contentText = "${latestNotification.senderNickname}: ${latestNotification.messageContent}"

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(contentTitle)
            .setContentText(contentText)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setShowWhen(true)
            .setWhen(latestNotification.timestamp)

        if (pendingNotifications.size > 1) {
            builder.setGroup(GROUP_KEY_DM)
        }

        if (messageCount > 1) {
            val style = NotificationCompat.InboxStyle()
                .setBigContentTitle(contentTitle)

            notifications.takeLast(5).forEach { notif ->
                style.addLine("${notif.senderNickname}: ${notif.messageContent}")
            }

            if (messageCount > 5) {
                val extra = messageCount - 5
                style.setSummaryText(context.resources.getQuantityString(R.plurals.notification_and_more, extra, extra))
            }

            builder.setStyle(style)
        } else {

            builder.setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(contentText)
            )
        }

        val notificationId = 4000
        notificationManager.notify(notificationId, builder.build())

        Log.d(TAG, "Displayed mesh mention notification: $contentTitle")
    }

    fun clearMeshMentionNotifications() {
        pendingNotifications.remove("mesh_mentions")

        val notificationId = 4000
        notificationManager.cancel(notificationId)

        if (pendingNotifications.isEmpty()) {
            notificationManager.cancel(SUMMARY_NOTIFICATION_ID)
        } else if (pendingNotifications.size == 1) {

            notificationManager.cancel(SUMMARY_NOTIFICATION_ID)
        } else {

            showSummaryNotification()
        }

        Log.d(TAG, "Cleared mesh mention notifications")
    }

    fun clearAllNotifications() {
        pendingNotifications.clear()
        notificationManager.cancelAll()
        pendingGeohashNotifications.clear()
        Log.d(TAG, "Cleared all notifications")
    }

    fun getPendingNotificationCount(): Int {
        return pendingNotifications.values.sumOf { it.size } +
               pendingGeohashNotifications.values.sumOf { it.size }
    }

    fun getAppBackgroundState(): Boolean {
        return isAppInBackground
    }

    fun getCurrentPrivateChatPeer(): String? {
        return currentPrivateChatPeer
    }

    fun getDebugInfo(): String {
        return buildString {
            appendLine("Notification Manager Debug Info:")
            appendLine("App in background: $isAppInBackground")
            appendLine("Current private chat peer: $currentPrivateChatPeer")
            appendLine("Current geohash: $currentGeohash")
            appendLine("Pending DM notifications: ${pendingNotifications.size} senders")
            pendingNotifications.forEach { (peerID, notifications) ->
                appendLine("  $peerID: ${notifications.size} messages")
            }
            appendLine("Pending geohash notifications: ${pendingGeohashNotifications.size} geohashes")
            pendingGeohashNotifications.forEach { (geohash, notifications) ->
                val mentions = notifications.count { it.isMention }
                val firstMessages = notifications.count { it.isFirstMessage }
                appendLine("  #$geohash: ${notifications.size} messages ($mentions mentions, $firstMessages first messages)")
            }
        }
    }
}
