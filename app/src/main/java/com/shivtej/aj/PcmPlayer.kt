package com.shivtej.aj

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper

/**
 * Gemini से आई आवाज़ (PCM) चलाना।
 * पहले पूरी आवाज़ एक साथ (static) चलाई जाती थी जिससे बीच में खरखराहट आ सकती थी;
 * अब छोटे-छोटे टुकड़ों में (streaming) चलती है, शुरू-आखिर में हल्का fade भी है ताकि "चट" की आवाज़ न आए।
 */
object PcmPlayer {

    private val main = Handler(Looper.getMainLooper())

    private class Session(val track: AudioTrack, val done: () -> Unit) {
        @Volatile
        var cancelled = false
    }

    @Volatile
    private var current: Session? = null

    fun play(pcm: ByteArray, sampleRate: Int, done: () -> Unit) {
        stop()
        val track = try {
            val minBuf = AudioTrack.getMinBufferSize(
                sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(maxOf(minBuf * 4, 48_000))
                .build()
        } catch (e: Exception) {
            main.post { done() }
            return
        }
        val session = Session(track, done)
        current = session
        val data = withFade(pcm, sampleRate)

        Thread {
            try {
                track.play()
                var offset = 0
                val chunk = 4096
                while (offset < data.size && !session.cancelled) {
                    val n = track.write(data, offset, minOf(chunk, data.size - offset))
                    if (n <= 0) break
                    offset += n
                }
                // बची हुई आवाज़ पूरी बजने तक रुको
                val totalFrames = data.size / 2
                var waited = 0
                while (!session.cancelled && track.playbackHeadPosition < totalFrames && waited < 4000) {
                    Thread.sleep(20)
                    waited += 20
                }
            } catch (_: Exception) {
            }
            try {
                track.stop()
            } catch (_: Exception) {
            }
            try {
                track.release()
            } catch (_: Exception) {
            }
            if (!session.cancelled) {
                main.post {
                    if (current === session) {
                        current = null
                        session.done()
                    }
                }
            }
        }.start()
    }

    /** बीच में रोकना (done नहीं बुलाता) */
    fun stop() {
        val s = current ?: return
        current = null
        s.cancelled = true
        try {
            s.track.pause()
            s.track.flush()
        } catch (_: Exception) {
        }
    }

    /** शुरू और आखिर के 15ms धीरे-धीरे ऊपर/नीचे, ताकि "चट" न बजे */
    private fun withFade(pcm: ByteArray, rate: Int): ByteArray {
        val out = pcm.copyOf()
        val samples = out.size / 2
        val fade = minOf(rate * 15 / 1000, samples / 2)
        for (i in 0 until fade) {
            scale(out, i, i.toFloat() / fade)
            scale(out, samples - 1 - i, i.toFloat() / fade)
        }
        return out
    }

    private fun scale(b: ByteArray, sampleIndex: Int, f: Float) {
        val i = sampleIndex * 2
        val v = ((b[i + 1].toInt() shl 8) or (b[i].toInt() and 0xFF)).toShort()
        val s = (v * f).toInt()
        b[i] = (s and 0xFF).toByte()
        b[i + 1] = ((s shr 8) and 0xFF).toByte()
    }
}
