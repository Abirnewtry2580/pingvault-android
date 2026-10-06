package com.pingvault.android

import android.app.Notification
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

class ArchiveNotificationListener : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        val notification = sbn.notification ?: return
        if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val extras = notification.extras ?: Bundle.EMPTY
        val appName = runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString()
        }.getOrDefault(sbn.packageName)
        val store = NotificationStore.get(this)
        val messages = extras.getParcelableArray(Notification.EXTRA_MESSAGES)
            ?.mapNotNull { it as? Bundle }
            .orEmpty()

        if (messages.isNotEmpty()) {
            messages.forEachIndexed { index, message ->
                val body = message.getCharSequence("text")?.toString().orEmpty()
                val media = runCatching {
                    captureMessageMedia(message, stableMessageKey(sbn.key, message, index))
                }.getOrNull()
                if (body.isBlank() && media == null) return@forEachIndexed

                val sender = message.getCharSequence("sender")?.toString()
                val title = sender?.takeIf { it.isNotBlank() }
                    ?: extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
                    ?: appName
                val postedAt = message.getLong("time", sbn.postTime).takeIf { it > 0L } ?: sbn.postTime
                store.put(
                    ArchivedNotification(
                        key = stableMessageKey(sbn.key, message, index),
                        packageName = sbn.packageName,
                        appName = appName,
                        title = title,
                        body = body,
                        postedAt = postedAt,
                        mediaPath = media?.first,
                        mediaMime = media?.second,
                        saved = false
                    )
                )
            }
        } else {
            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
            val body = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
                ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
            val media = runCatching { captureNotificationPicture(notification, sbn.key) }.getOrNull()
            if (title.isNotBlank() || body.isNotBlank() || media != null) {
                store.put(
                    ArchivedNotification(
                        key = sbn.key,
                        packageName = sbn.packageName,
                        appName = appName,
                        title = title.ifBlank { appName },
                        body = body,
                        postedAt = sbn.postTime,
                        mediaPath = media?.first,
                        mediaMime = media?.second,
                        saved = false
                    )
                )
            }
        }
        sendBroadcast(Intent(ACTION_ARCHIVE_UPDATED).setPackage(packageName))
    }

    private fun stableMessageKey(
        notificationKey: String,
        message: Bundle,
        index: Int
    ): String {
        val timestamp = message.getLong("time", 0L)
        val text = message.getCharSequence("text")?.toString().orEmpty()
        val mediaUri = getMessageUri(message)?.toString().orEmpty()
        val raw = "$notificationKey|$timestamp|$text|$mediaUri|$index"
        return MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    @Suppress("DEPRECATION")
    private fun getMessageUri(message: Bundle): Uri? =
        message.getParcelable<Uri>("uri")
            ?: message.getParcelable("dataUri")
            ?: message.getParcelable("android.dataUri")

    private fun getMessageMime(message: Bundle): String? =
        message.getString("type")
            ?: message.getString("dataMimeType")
            ?: message.getString("android.mimeType")

    @Suppress("DEPRECATION")
    private fun captureNotificationPicture(
        notification: Notification,
        key: String
    ): Pair<String, String>? {
        val extras = notification.extras ?: return null
        val bitmap = extras.get(Notification.EXTRA_PICTURE) as? Bitmap ?: return null
        val file = File(mediaFolder(), stableKey(key) + ".jpg")
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        return file.absolutePath to "image/jpeg"
    }

    private fun captureMessageMedia(message: Bundle, key: String): Pair<String, String>? {
        val uri = getMessageUri(message) ?: return null
        val mime = getMessageMime(message) ?: contentResolver.getType(uri) ?: return null
        val extension = when {
            mime.startsWith("image/") -> "img"
            mime.startsWith("video/") -> "vid"
            mime.startsWith("audio/") -> "aud"
            else -> "bin"
        }
        val file = File(mediaFolder(), stableKey(key) + "." + extension)
        val copied = runCatching {
            val input = contentResolver.openInputStream(uri) ?: return@runCatching false
            var total = 0L
            input.use { source ->
                FileOutputStream(file).use { sink ->
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        val count = source.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > MAX_MEDIA_BYTES) break
                        sink.write(buffer, 0, count)
                    }
                }
            }
            total in 1..MAX_MEDIA_BYTES
        }.getOrDefault(false)
        if (!copied) {
            file.delete()
            return null
        }
        return file.absolutePath to mime
    }

    private fun mediaFolder() = File(filesDir, "captured_media").apply { mkdirs() }

    private fun stableKey(key: String): String =
        MessageDigest.getInstance("SHA-256").digest(key.toByteArray())
            .joinToString("") { "%02x".format(it) }

    companion object {
        const val ACTION_ARCHIVE_UPDATED = "com.pingvault.android.ARCHIVE_UPDATED"
        private const val MAX_MEDIA_BYTES = 25L * 1024 * 1024
    }
}
