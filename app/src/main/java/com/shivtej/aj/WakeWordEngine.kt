package com.shivtej.aj

import android.content.Context
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.SpeechService
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.zip.ZipInputStream

/**
 * "AJ" सुनते ही जगाने वाला हिस्सा।
 * Vosk नाम की free, open-source आवाज़ पहचान फ़ोन के अंदर ही चलती है (internet नहीं चाहिए)।
 * यह सिर्फ "AJ" शब्द पहचानने के लिए सुनता है, बाकी कुछ नहीं समझता या सेव करता।
 */
class WakeWordEngine(private val ctx: Context, private val onWake: () -> Unit) {

    companion object {
        // Indian English का छोटा model (≈36 MB), भारतीय उच्चारण वाला "ए जे" अच्छे से पकड़ता है
        const val MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-en-in-0.4.zip"

        // सिर्फ इन्हीं शब्दों पर ध्यान, बाकी सब [unk]
        private const val GRAMMAR = "[\"a j\", \"a jay\", \"hey a j\", \"[unk]\"]"
        private val WAKE = Regex("(^| )a (j|jay)( |$)")

        @Volatile
        var downloading = false
            private set

        fun modelDir(ctx: Context): File? {
            val base = File(ctx.filesDir, "vosk")
            return base.listFiles()?.firstOrNull { it.isDirectory && File(it, "conf").exists() }
        }

        fun isModelReady(ctx: Context) = modelDir(ctx) != null

        /** एक बार model download करना (free)। progress और done background thread से आते हैं */
        fun downloadModel(ctx: Context, progress: (String) -> Unit, done: (Boolean) -> Unit) {
            if (downloading) return
            downloading = true
            Thread {
                var ok = false
                try {
                    val base = File(ctx.filesDir, "vosk")
                    base.deleteRecursively()
                    base.mkdirs()
                    val zip = File(ctx.cacheDir, "vosk-model.zip")
                    val conn = URL(MODEL_URL).openConnection() as HttpURLConnection
                    conn.connectTimeout = 20000
                    conn.readTimeout = 30000
                    conn.instanceFollowRedirects = true
                    val total = conn.contentLengthLong
                    conn.inputStream.use { input ->
                        FileOutputStream(zip).use { out ->
                            val buf = ByteArray(64 * 1024)
                            var read = 0
                            var sum = 0L
                            var lastShown = -1L
                            while (input.read(buf).also { read = it } != -1) {
                                out.write(buf, 0, read)
                                sum += read
                                val mb = sum / (1024 * 1024)
                                if (mb != lastShown) {
                                    lastShown = mb
                                    val pct = if (total > 0) " (${sum * 100 / total}%)" else ""
                                    progress("डाउनलोड हो रहा है: $mb MB$pct")
                                }
                            }
                        }
                    }
                    conn.disconnect()
                    progress("Model खोल रहा हूँ...")
                    unzip(zip, base)
                    zip.delete()
                    ok = isModelReady(ctx)
                    progress(if (ok) "✅ Model तैयार है" else "Model ठीक से नहीं खुला, फिर से download करें")
                } catch (e: Exception) {
                    progress("डाउनलोड नहीं हुआ: ${e.message ?: "internet देखें"}")
                } finally {
                    downloading = false
                    done(ok)
                }
            }.start()
        }

        private fun unzip(zip: File, target: File) {
            val targetPath = target.canonicalPath + File.separator
            ZipInputStream(zip.inputStream().buffered()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val out = File(target, entry.name)
                    if (!out.canonicalPath.startsWith(targetPath)) throw SecurityException("गलत zip")
                    if (entry.isDirectory) {
                        out.mkdirs()
                    } else {
                        out.parentFile?.mkdirs()
                        FileOutputStream(out).use { zis.copyTo(it) }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        }
    }

    private var model: Model? = null
    private var service: SpeechService? = null
    private val io = Executors.newSingleThreadExecutor()
    private val lock = Any()

    @Volatile
    private var wanted = false

    private val listener = object : org.vosk.android.RecognitionListener {
        override fun onPartialResult(hypothesis: String?) {}
        override fun onResult(hypothesis: String?) = check(hypothesis)
        override fun onFinalResult(hypothesis: String?) = check(hypothesis)
        override fun onError(exception: Exception?) {
            AjBus.setStatus("⚠ Wake word में गड़बड़: ${exception?.message ?: ""}")
        }
        override fun onTimeout() {}
    }

    private fun check(hypothesis: String?) {
        if (hypothesis.isNullOrBlank() || !wanted) return
        val text = try {
            JSONObject(hypothesis).optString("text")
        } catch (e: Exception) {
            return
        }
        if (WAKE.containsMatchIn(text)) onWake()
    }

    fun isRunning(): Boolean = synchronized(lock) { service != null }

    fun start() {
        wanted = true
        io.execute {
            if (!wanted || isRunning()) return@execute
            val dir = modelDir(ctx)
            if (dir == null) {
                AjBus.setStatus("⚠ ‘AJ’ सुनने के लिए पहले model download करें")
                return@execute
            }
            try {
                val m = model ?: Model(dir.absolutePath).also { model = it }
                synchronized(lock) {
                    if (!wanted || service != null) return@execute
                    val recognizer = Recognizer(m, 16000.0f, GRAMMAR)
                    val s = SpeechService(recognizer, 16000.0f)
                    s.startListening(listener)
                    service = s
                }
                AjBus.setStatus("✅ ‘AJ’ बोलिए, मैं सुन रहा हूँ")
            } catch (e: Exception) {
                AjBus.setStatus("⚠ Wake word शुरू नहीं हुआ: ${e.message ?: ""}")
            }
        }
    }

    /** तुरंत माइक छोड़ देता है, ताकि बात सुनने वाला हिस्सा माइक ले सके */
    fun stop() {
        wanted = false
        synchronized(lock) {
            service?.let {
                try {
                    it.stop()
                    it.shutdown()
                } catch (_: Exception) {
                }
            }
            service = null
        }
    }

    fun release() {
        stop()
        io.execute {
            try {
                model?.close()
            } catch (_: Exception) {
            }
            model = null
        }
        io.shutdown()
    }
}
