package dev.pockethost.supervisor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import dev.pockethost.MainActivity

class ServerForegroundService : Service() {
    override fun onCreate() {
        super.onCreate()
        createChannel()
        AppPaths.ensure(this)
        LogBus.attach(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground(buildNotification("PocketHost supervisor running"))
        when (intent?.action ?: ACTION_START_ALL) {
            ACTION_START_ALL -> ProcessSupervisor.startAll(this)
            ACTION_STOP_ALL -> {
                ProcessSupervisor.stopAll(this)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            ACTION_START_ONE -> intent?.getStringExtra(EXTRA_SERVICE_ID)?.let { ProcessSupervisor.start(this, it) }
            ACTION_STOP_ONE -> intent?.getStringExtra(EXTRA_SERVICE_ID)?.let { ProcessSupervisor.stop(this, it) }
            ACTION_RESTART_ONE -> intent?.getStringExtra(EXTRA_SERVICE_ID)?.let { ProcessSupervisor.restart(this, it) }
            ACTION_RESTART_ALL -> ProcessSupervisor.restartAll(this)
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startInForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "PocketHost supervisor",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps PocketHost daemons running."
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, ServerForegroundService::class.java).setAction(ACTION_STOP_ALL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("PocketHost")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_media_pause, "Stop all", stopIntent)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "pockethost_supervisor"
        const val NOTIFICATION_ID = 4401
        const val ACTION_START_ALL = "dev.pockethost.action.START_ALL"
        const val ACTION_STOP_ALL = "dev.pockethost.action.STOP_ALL"
        const val ACTION_START_ONE = "dev.pockethost.action.START_ONE"
        const val ACTION_STOP_ONE = "dev.pockethost.action.STOP_ONE"
        const val ACTION_RESTART_ONE = "dev.pockethost.action.RESTART_ONE"
        const val ACTION_RESTART_ALL = "dev.pockethost.action.RESTART_ALL"
        const val EXTRA_SERVICE_ID = "service_id"
    }
}
