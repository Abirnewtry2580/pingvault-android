package com.pingvault.android

import android.app.Notification
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
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
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val body = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_TEXT)
            ?: messagingText(extras))?.toString().orEmpty()
        if (title.isBlank() && body.isBlank() && !extras.containsKey(Notification.EXTRA_PICTURE)) return

        val appName = runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString()
        }.getOrDefault(sbn.packageName)
        val media = runCatching { captureMedia(notification, sbn.key) }.getOrNull()
        NotificationStore.get(this).put(
            ArchivedNotification(
                key = sbn.key, packageName = sbn.packageName, appName = appName,
                title = title.ifBlank { appName }, body = body, postedAt = sbn.postTime,
                mediaPath = media?.first, mediaMime = media?.second, saved = false
            )
        )
        sendBroadcast(Intent(ACTION_ARCHIVE_UPDATED).setPackage(packageName))
    }

    private fun messagingText(extras: Bundle): CharSequence? {
        val messages = extras.getParcelableArray(Notification.EXTRA_MESSAGES) ?: return null
        for (i in messages.indices.reversed()) {
            val bundle = messages[i] as? Bundle ?: continue
            bundle.getCharSequence("text")?.let { if (it.isNotBlank()) return it }
        }
        return null
    }

    @Suppress("DEPRECATION")
    private fun captureMedia(notification: Notification, key: String): Pair<String, String>? {
        val extras = notification.extras ?: return null
        val folder = File(filesDir, "captured_media").apply { mkdirs() }
        val base = MessageDigest.getInstance("SHA-256").digest(key.toByteArray())
            .joinToString("") { "%02x".format(it) }

        val bitmap = extras.get(Notification.EXTRA_PICTURE) as? Bitmap
        if (bitmap != null) {
            val file = File(folder, "$base.jpg")
            FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }
            return file.absolutePath to "image/jpeg"
        }

        val messages = extras.getParcelableArray(Notification.EXTRA_MESSAGES) ?: return null
        for (i in messages.indices.reversed()) {
            val bundle = messages[i] as? Bundle ?: continue
            val uri = bundle.getParcelable<android.net.Uri>("uri")
                ?: bundle.getParcelable("dataUri")
                ?: bundle.getParcelable("android.dataUri")
            val mime = bundle.getString("type") ?: bundle.getString("dataMimeType")
                ?: bundle.getString("android.mimeType")
            if (uri == null || mime.isNullOrBlank()) continue
            val ext = when {
                mime.startsWith("image/") -> "img"
                mime.startsWith("video/") -> "vid"
                mime.startsWith("audio/") -> "aud"
                else -> "bin"
            }
            val file = File(folder, "$base.$ext")
            val copied = runCatching {
                val input = contentResolver.openInputStream(uri) ?: return@runCatching false
                input.use { source -> FileOutputStream(file).use { sink -> source.copyTo(sink, 64 * 1024) } }
                file.length() in 1..(25L * 1024 * 1024)
            }.getOrDefault(false)
            if (copied) return file.absolutePath to mime
            file.delete()
        }
        return null
    }

    companion object {
        const val ACTION_ARCHIVE_UPDATED = "com.pingvault.android.ARCHIVE_UPDATED"
    }
}
