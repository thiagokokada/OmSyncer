package com.github.thiagokokada.omronsyncer.sync

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
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
            .setOngoing(true)
            .setOnlyAlertOnce(true)
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
        fetched: Int,
        inserted: Int,
        duplicates: Int,
        exportedToHealthConnect: Boolean,
    ) {
        if (!hasNotificationPermission(context)) {
            return
        }
        ensureNotificationChannel(context)
        val timestamp = timestampText()
        val body = context.getString(
            if (exportedToHealthConnect) {
                R.string.sync_success_notification_body_health_connect
            } else {
                R.string.sync_success_notification_body
            },
            timestamp,
            fetched,
            inserted,
            duplicates,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setContentTitle(context.getString(R.string.sync_success_notification_title))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setContentIntent(launchPendingIntent(context))
            .build()

        NotificationManagerCompat.from(context).notify(notificationId, notification)
    }

    private fun ensureNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
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

    private fun timestampText(): String {
        return SUCCESS_TIME_FORMATTER.format(Instant.now().atZone(ZoneId.systemDefault()))
    }

    private fun hasNotificationPermission(context: Context): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private val SUCCESS_TIME_FORMATTER: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
}
