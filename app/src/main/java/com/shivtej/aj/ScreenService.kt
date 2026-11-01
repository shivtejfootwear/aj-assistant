package com.shivtej.aj

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

/**
 * Screen की live झलक: हर ~2 सेकंड में screen का एक frame लेकर cloud पर भेजता है।
 * चलते वक्त notification दिखता है; Android भी screen-cast निशान दिखाता है।
 */
class ScreenService : Service() {

    companion object {
        private const val CHANNEL = "aj_screen"
        private const val NOTIF_ID = 11
    }

    private val worker = Executors.newSingleThreadExecutor()
    private var projection: MediaProjection? = null
    private var vd: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    @Volatile private var running = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel()
        goForeground()
        val code = intent?.getIntExtra("code", 0) ?: 0
        val data = if (Build.VERSION.SDK_INT >= 33) intent?.getParcelableExtra("data", Intent::class.java)
            else @Suppress("DEPRECATION") intent?.getParcelableExtra<Intent>("data")
        val sec = (intent?.getIntExtra("sec", 120) ?: 120).coerceIn(10, 600)
        if (data == null) { stopSelf(); return START_NOT_STICKY }

        try {
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = mpm.getMediaProjection(code, data)
            projection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { stopAll() }
            }, null)
            start(sec)
        } catch (e: Exception) {
            stopAll()
        }
        return START_NOT_STICKY
    }

    private fun start(sec: Int) {
        val dm = resources.displayMetrics
        val w = (dm.widthPixels / 2).coerceAtLeast(360)
        val h = (dm.heightPixels / 2).coerceAtLeast(640)
        val dpi = dm.densityDpi
        thread = HandlerThread("aj-screen").apply { start() }
        handler = Handler(thread!!.looper)
        reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        vd = projection?.createVirtualDisplay(
            "aj-screen", w, h, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader!!.surface, null, handler
        )
        AjBus.setStatus("🖥 Screen is being shared")
        running = true
        val devId = Store(this).deviceId
        val uid = Store(this).userId.ifBlank { "unknown" }
        val deadline = System.currentTimeMillis() + sec * 1000L

        fun grab() {
            if (!running || System.currentTimeMillis() > deadline) { stopAll(); return }
            val bytes = capture(w, h)
            worker.execute {
                if (bytes != null) {
                    try { MediaUpload.upload(this, "$uid/live/screen_$devId.jpg", bytes, "image/jpeg") } catch (_: Exception) {}
                }
                handler?.postDelayed({ grab() }, 1800)
            }
        }
        handler?.postDelayed({ grab() }, 1200)
    }

    private fun capture(w: Int, h: Int): ByteArray? {
        return try {
            val image = reader?.acquireLatestImage() ?: return null
            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * w
            val bmpW = w + rowPadding / pixelStride
            val full = Bitmap.createBitmap(bmpW, h, Bitmap.Config.ARGB_8888)
            full.copyPixelsFromBuffer(buffer)
            image.close()
            val bmp = if (bmpW != w) Bitmap.createBitmap(full, 0, 0, w, h) else full
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 70, out)
            if (bmp !== full) full.recycle()
            bmp.recycle()
            out.toByteArray()
        } catch (e: Exception) {
            null
        }
    }

    private fun stopAll() {
        running = false
        try { vd?.release() } catch (_: Exception) {}
        try { reader?.close() } catch (_: Exception) {}
        try { projection?.stop() } catch (_: Exception) {}
        try { thread?.quitSafely() } catch (_: Exception) {}
        AjBus.setStatus("✅ Ready")
        try { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) } catch (_: Exception) {}
        stopSelf()
    }

    override fun onDestroy() {
        running = false
        worker.shutdown()
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Screen share", NotificationManager.IMPORTANCE_HIGH))
        }
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("My Devices")
            .setContentText("🖥 Screen is being shared")
            .setOngoing(true)
            .build()

    private fun goForeground() {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0
        try {
            ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(), type)
        } catch (e: Exception) {
            stopSelf()
        }
    }
}
