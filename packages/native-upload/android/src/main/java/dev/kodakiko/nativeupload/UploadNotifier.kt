package dev.kodakiko.nativeupload

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

class UploadNotifier(private val context: Context) {
  private var lastNotifiedAt = 0L

  fun createChannel() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val channel = NotificationChannel(
      UploadConstants.CHANNEL_ID,
      context.getString(R.string.upload_channel_name),
      NotificationManager.IMPORTANCE_LOW,
    )
    context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
  }

  fun build(): Notification {
    val itemsLeft = UploadRegistry.itemsLeft()
    val (_, total) = UploadRegistry.progress()
    val percent = UploadRegistry.percent()
    return NotificationCompat.Builder(context, UploadConstants.CHANNEL_ID)
      .setSmallIcon(android.R.drawable.stat_sys_upload)
      .setContentTitle(context.getString(R.string.upload_notification_title))
      .setContentText(
        context.resources.getQuantityString(
          R.plurals.upload_notification_items_left,
          itemsLeft,
          itemsLeft,
        ),
      )
      .setOnlyAlertOnce(true)
      .setOngoing(true)
      .setProgress(UploadConstants.PERCENT_MAX, percent, total == 0L)
      .build()
  }

  fun update(now: Long) {
    if (now - lastNotifiedAt < UploadConstants.PROGRESS_NOTIFY_INTERVAL_MS) return
    lastNotifiedAt = now
    try {
      NotificationManagerCompat.from(context).notify(UploadConstants.NOTIFICATION_ID, build())
    } catch (denied: SecurityException) {
      return
    }
  }
}
