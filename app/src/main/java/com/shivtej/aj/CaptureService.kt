package com.shivtej.aj

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.MediaActionSound
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * दूसरे फ़ोन से माँगने पर फ़ोटो लेना या आवाज़ record करना — पर कुछ छुपाकर नहीं।
 * जब तक यह चलता है, इस फ़ोन पर:
 *  - एक notification दिखता है ("AJ अभी camera/रिकॉर्डिंग चला रहा है")
 *  - shutter या beep की आवाज़ आती है
 *  - Android खुद status bar में हरा camera/mic निशान दिखाता है
 * यह असली CCTV/voice-recorder apps की तरह है, जो छुपते नहीं।
 */
class CaptureService : Service() {

    companion object {
        private const val CHANNEL = "aj_capture"
        private const val NOTIF_ID = 9
        private const val EXTRA_ID = "cmd_id"
        private const val EXTRA_TYPE = "type"
        private const val EXTRA_SEC = "sec"

        fun start(ctx: Context, commandId: Long, type: String, sec: Int) {
            val i = Intent(ctx, CaptureService::class.java)
                .putExtra(EXTRA_ID, commandId)
                .putExtra(EXTRA_TYPE, type)
                .putExtra(EXTRA_SEC, sec)
            ContextCompat.startForegroundService(ctx, i)
        }
    }

    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var recorder: MediaRecorder? = null
    private var cmdId = -1L
    @Volatile
    private var donePosted = false

    private fun done(id: Long, result: JSONObject) {
        donePosted = true
        DeviceSync.markDone(this, id, result)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getLongExtra(EXTRA_ID, -1) ?: -1
        cmdId = id
        val type = intent?.getStringExtra(EXTRA_TYPE) ?: "photo_back"
        val sec = (intent?.getIntExtra(EXTRA_SEC, 15) ?: 15).coerceIn(3, 120)

        createChannel()
        val isPhoto = type.startsWith("photo")
        goForeground(isPhoto, if (isPhoto) "AJ दूसरे फ़ोन के कहने पर फ़ोटो ले रहा है" else "AJ दूसरे फ़ोन के कहने पर आवाज़ record कर रहा है")

        if (isPhoto) doPhoto(id, type == "photo_front") else doRecord(id, sec)
        return START_NOT_STICKY
    }

    // ---------------- फ़ोटो ----------------

    private fun doPhoto(id: Long, front: Boolean) {
        AjBus.setStatus("📸 दूसरे फ़ोन के कहने पर फ़ोटो ली जा रही है")
        shutterSound()
        CameraCapture.capture(this, front) { bytes ->
            worker.execute {
                var ok = false
                if (bytes != null) {
                    val path = "${uid()}/photo/${stamp()}_${if (front) "front" else "back"}.jpg"
                    if (MediaUpload.upload(this, path, bytes, "image/jpeg") != null) {
                        done(id, JSONObject().put("ok", true).put("kind", "photo").put("path", path))
                        ok = true
                    }
                }
                if (!ok) done(id, JSONObject().put("ok", false).put("error", "फ़ोटो नहीं बनी"))
                finish()
            }
        }
    }

    // ---------------- रिकॉर्डिंग ----------------

    private fun doRecord(id: Long, sec: Int) {
        AjBus.setStatus("🎙 दूसरे फ़ोन के कहने पर आवाज़ record हो रही है")
        beep()
        val file = File(cacheDir, "rec_${System.currentTimeMillis()}.m4a")
        val rec = try {
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(this) else @Suppress("DEPRECATION") MediaRecorder()).apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(96000)
                setAudioSamplingRate(44100)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }
        } catch (e: Exception) {
            done(id, JSONObject().put("ok", false).put("error", "रिकॉर्डिंग शुरू नहीं हुई"))
            finish()
            return
        }
        recorder = rec

        main.postDelayed({
            var ok = false
            try {
                rec.stop()
            } catch (_: Exception) {
            }
            try {
                rec.release()
            } catch (_: Exception) {
            }
            recorder = null
            beep()
            worker.execute {
                val bytes = try {
                    if (file.exists()) file.readBytes() else null
                } catch (e: Exception) {
                    null
                }
                if (bytes != null && bytes.isNotEmpty()) {
                    val path = "${uid()}/audio/${stamp()}.m4a"
                    if (MediaUpload.upload(this, path, bytes, "audio/mp4") != null) {
                        done(id, JSONObject().put("ok", true).put("kind", "audio").put("path", path))
                        ok = true
                    }
                }
                try {
                    file.delete()
                } catch (_: Exception) {
                }
                if (!ok) done(id, JSONObject().put("ok", false).put("error", "रिकॉर्डिंग सेव नहीं हुई"))
                finish()
            }
        }, sec * 1000L)
    }

    // ---------------- helper ----------------

    private fun uid() = Store(this).userId.ifBlank { "unknown" }
    private fun stamp() = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    private fun shutterSound() {
        try {
            MediaActionSound().play(MediaActionSound.SHUTTER_CLICK)
        } catch (_: Exception) {
        }
    }

    private fun beep() {
        try {
            val t = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 90)
            t.startTone(ToneGenerator.TONE_PROP_BEEP, 250)
            main.postDelayed({ t.release() }, 500)
        } catch (_: Exception) {
        }
    }

    private fun finish() {
        // अगर किसी वजह से result नहीं लिखा गया, तो command को failed कर दो (वरना बार-बार कोशिश होगी)
        if (!donePosted && cmdId > 0) {
            val id = cmdId
            worker.execute { DeviceSync.markDone(this, id, JSONObject().put("ok", false).put("error", "capture नहीं हो पाया")) }
            donePosted = true
        }
        main.post {
            AjBus.setStatus("✅ AJ तैयार है")
            try {
                androidx.core.app.ServiceCompat.stopForeground(this, androidx.core.app.ServiceCompat.STOP_FOREGROUND_REMOVE)
            } catch (_: Exception) {
            }
            stopSelf()
        }
    }

    override fun onDestroy() {
        try {
            recorder?.release()
        } catch (_: Exception) {
        }
        recorder = null
        worker.shutdown()
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            val ch = NotificationChannel(CHANNEL, "AJ camera/रिकॉर्डिंग", NotificationManager.IMPORTANCE_HIGH)
            ch.description = "जब AJ दूसरे फ़ोन के कहने पर camera या रिकॉर्डिंग चलाए"
            nm.createNotificationChannel(ch)
        }
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("AJ")
            .setContentText(text)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

    private fun goForeground(isPhoto: Boolean, text: String) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (isPhoto) ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            else ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else 0
        try {
            androidx.core.app.ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(text), type)
        } catch (e: Exception) {
            finish()
        }
    }
}
