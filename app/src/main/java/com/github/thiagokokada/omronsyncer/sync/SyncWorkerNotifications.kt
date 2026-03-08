package com.github.thiagokokada.omronsyncer.sync

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import com.github.thiagokokada.omronsyncer.R

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
}
