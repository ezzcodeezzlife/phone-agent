package dev.phoneagent

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

/**
 * Foreground service that runs only while a ChatGPT sign-in waits for its browser redirect.
 *
 * During sign-in the browser has the screen and the app is in the background. Android then freezes
 * (or on some phones kills) the app process, so the redirect to the loopback listener on
 * 127.0.0.1:1455 connects but never gets an answer and the page loads forever. A foreground
 * service keeps the process running until [Auth] reports the result.
 *
 * Call [start] and [stop] on the main thread.
 */
class SignInService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Sign-in", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Phone Agent")
            .setContentText("Waiting for the ChatGPT sign-in in the browser…")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        // startForeground() must always follow startForegroundService(), even if the sign-in already ended.
        if (Build.VERSION.SDK_INT >= 34) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(ID, n)
        running = this
        if (!wanted) stopSelf()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (running === this) running = null
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "signin"
        private const val ID = 1
        private var wanted = false
        private var running: SignInService? = null

        fun start(ctx: Context) {
            wanted = true
            try {
                ctx.startForegroundService(Intent(ctx, SignInService::class.java))
            } catch (e: Exception) {
                // Not fatal: the sign-in still works whenever the system leaves the app running.
                AgentBus.log("Could not keep the app awake during sign-in: ${e.message}")
            }
        }

        fun stop() {
            wanted = false
            running?.stopSelf()
        }
    }
}
