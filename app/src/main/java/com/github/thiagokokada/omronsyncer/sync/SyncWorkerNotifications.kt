package com.github.thiagokokada.omronsyncer.sync

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import com.github.thiagokokada.omronsyncer.R
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object SyncWorkerNotifications {
    const val CHANNEL_ID = "background_sync"

    fun showRunningSync(
        context: Context,
        notificationId: Int,
        titleResId: Int,
        bodyResId: Int,
    ) {
        if (!hasNotificationPermission(context)) {
            return
        }

        ensureNotificationChannel(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(context.getString(titleResId))
            .setContentText(context.getString(bodyResId))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(0, 0, true)
            .setContentIntent(launchPendingIntent(context))
            .build()

        NotificationManagerCompat.from(context).notify(notificationId, notification)
    }

    fun createForegroundInfo(
        context: Context,
        notificationId: Int,
        titleResId: Int,
        bodyResId: Int,
    ): ForegroundInfo {
        ensureNotificationChannel(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(context.getString(titleResId))
            .setContentText(context.getString(bodyResId))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(0, 0, true)
            .build()

        return ForegroundInfo(
            notificationId,
            notification,
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    suspend fun promoteToForegroundIfAllowed(
        worker: CoroutineWorker,
        context: Context,
        notificationId: Int,
        titleResId: Int,
        bodyResId: Int,
        logTag: String,
    ): Boolean {
        return runCatching {
            worker.setForeground(
                createForegroundInfo(
                    context = context,
                    notificationId = notificationId,
                    titleResId = titleResId,
                    bodyResId = bodyResId,
                ),
            )
        }.onFailure { error ->
            Log.w(logTag, "Foreground promotion not allowed; continuing without it.", error)
        }.isSuccess
    }

    fun showSuccessfulSync(
        context: Context,
        notificationId: Int,
        titleResId: Int = R.string.sync_success_notification_title,
        inserted: Int,
        exportedToHealthConnect: Boolean,
    ) {
        if (!hasNotificationPermission(context)) {
            return
        }
        if (!shouldShowSuccessfulSyncNotification(inserted)) {
            return
        }

        ensureNotificationChannel(context)
        val body = SyncUserMessageFormatter.successNotificationBody(
            context = context,
            timestampText = timestampText(),
            inserted = inserted,
            exportedToHealthConnect = exportedToHealthConnect,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setContentTitle(context.getString(titleResId))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setContentIntent(launchPendingIntent(context))
            .build()

        NotificationManagerCompat.from(context).notify(notificationId, notification)
    }

    fun dismiss(context: Context, notificationId: Int) {
        NotificationManagerCompat.from(context).cancel(notificationId)
    }

    private fun ensureNotificationChannel(context: Context) {
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val existingChannel = notificationManager.getNotificationChannel(CHANNEL_ID)
        if (existingChannel != null) {
            return
        }

        notificationManager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.background_sync_notification_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    private fun launchPendingIntent(context: Context): PendingIntent? {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            ?: return null
        return PendingIntent.getActivity(
            context,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun hasNotificationPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun timestampText(): String {
        return SUCCESS_TIME_FORMATTER.format(Instant.now().atZone(ZoneId.systemDefault()))
    }

    private val SUCCESS_TIME_FORMATTER: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
}

internal fun shouldShowSuccessfulSyncNotification(
    insertedMeasurementCount: Int,
): Boolean {
    return insertedMeasurementCount > 0
}
