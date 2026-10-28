package com.shivtej.aj

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors

/**
 * यह फ़ोन आपके admin से जुड़ा रहे, इसके लिए चलता रहने वाला service।
 * हर कुछ सेकंड: इस फ़ोन की जानकारी cloud पर भेजता है और admin के भेजे काम (locate/ring/photo/record) करता है।
 * notification हमेशा दिखता है, ताकि फ़ोन इस्तेमाल करने वाले को पता रहे कि यह जुड़ा हुआ है।
 */
class AjService : Service() {

    companion object {
        const val ACTION_START = "com.shivtej.aj.START"
        const val ACTION_STOP = "com.shivtej.aj.STOP"
        private const val CHANNEL = "aj_link"
        private const val NOTIF_ID = 7

        fun start(ctx: Context) = send(ctx, ACTION_START)
        fun send(ctx: Context, action: String) {
            val i = Intent(ctx, AjService::class.java).setAction(action)
            ContextCompat.startForegroundService(ctx, i)
        }
    }

    private lateinit var store: Store
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()

    private val tick = object : Runnable {
        override fun run() {
            worker.execute {
                try {
                    DeviceSync.runPendingCommands(this@AjService)
                } catch (_: Exception) {
                }
            }
            main.postDelayed(this, 45_000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        store = Store(this)
        createChannel()
        goForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground()
        if (intent?.action == ACTION_STOP || !store.loggedIn) {
            main.removeCallbacks(tick)
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        main.removeCallbacks(tick)
        main.postDelayed(tick, 1500)
        return START_STICKY
    }

    override fun onDestroy() {
        main.removeCallbacks(tick)
        worker.shutdown()
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            val ch = NotificationChannel(CHANNEL, "AJ link", NotificationManager.IMPORTANCE_LOW)
            ch.description = "This phone is linked to your admin"
            nm.createNotificationChannel(ch)
        }
    }

    private fun buildNotification(): Notification {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), flags)
        val stop = PendingIntent.getService(
            this, 2, Intent(this, AjService::class.java).setAction(ACTION_STOP), flags
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("AJ")
            .setContentText("This phone is linked to your admin")
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, "Stop", stop)
            .build()
    }

    private fun goForeground() {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        try {
            ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(), type)
        } catch (e: Exception) {
            stopSelf()
        }
    }
}
