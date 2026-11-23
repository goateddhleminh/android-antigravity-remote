package com.agyremote.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.agyremote.app.MainActivity

/**
 * Keeps the active terminal session WebSocket alive while the user switches apps or locks the screen.
 */
class TerminalConnectionService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val hostName = intent?.getStringExtra(EXTRA_HOST_NAME) ?: "PC"
        val sessionName = intent?.getStringExtra(EXTRA_SESSION_NAME) ?: "Terminal"
        val status = intent?.getStringExtra(EXTRA_STATUS) ?: "Connected"

        val notification = buildNotification(hostName, sessionName, status)
        startForeground(NOTIFICATION_ID, notification)
        return START_STICKY
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Active Terminal Sessions",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Keeps your remote Antigravity terminal session alive in the background"
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(hostName: String, sessionName: String, status: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPending = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val stopIntent = Intent(this, TerminalConnectionService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPending = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentTitle("Antigravity Remote • $hostName")
            .setContentText("$sessionName ($status)")
            .setOngoing(true)
            .setContentIntent(openPending)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPending)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "agy_remote_terminal_channel"
        private const val NOTIFICATION_ID = 4201
        const val ACTION_STOP = "com.agyremote.app.action.STOP_SERVICE"
        const val EXTRA_HOST_NAME = "host_name"
        const val EXTRA_SESSION_NAME = "session_name"
        const val EXTRA_STATUS = "status"

        fun start(context: Context, hostName: String, sessionName: String, status: String = "Connected") {
            val intent = Intent(context, TerminalConnectionService::class.java).apply {
                putExtra(EXTRA_HOST_NAME, hostName)
                putExtra(EXTRA_SESSION_NAME, sessionName)
                putExtra(EXTRA_STATUS, status)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TerminalConnectionService::class.java))
        }
    }
}
