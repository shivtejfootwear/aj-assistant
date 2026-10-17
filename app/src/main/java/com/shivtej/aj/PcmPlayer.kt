package com.shivtej.aj

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper

/** Gemini से आई आवाज़ (PCM) चलाना। सब main thread पर। */
object PcmPlayer {

    private val main = Handler(Looper.getMainLooper())
    private var track: AudioTrack? = null
    private var onDone: (() -> Unit)? = null

    fun play(pcm: ByteArray, sampleRate: Int, done: () -> Unit) {
        main.post { start(pcm, sampleRate, done) }
    }

    private fun start(pcm: ByteArray, sampleRate: Int, done: () -> Unit) {
        stop()
        val t = try {
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
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(pcm.size)
                .build()
        } catch (e: Exception) {
            done()
            return
        }
        t.write(pcm, 0, pcm.size)
        track = t
        onDone = done
        val frames = pcm.size / 2
        t.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
            override fun onMarkerReached(at: AudioTrack?) = finish(t)
            override fun onPeriodicNotification(at: AudioTrack?) {}
        }, main)
        t.notificationMarkerPosition = frames
        t.play()
        // marker न आए तो भी समय पूरा होते ही आगे बढ़ो
        val ms = frames * 1000L / sampleRate + 1200
        main.postDelayed({ finish(t) }, ms)
    }

    private fun finish(t: AudioTrack) {
        if (track !== t) return
        val cb = onDone
        release()
        cb?.invoke()
    }

    /** बीच में रोकना (done नहीं बुलाता) */
    fun stop() {
        release()
    }

    private fun release() {
        track?.let {
            try {
                it.stop()
            } catch (_: Exception) {
            }
            try {
                it.release()
            } catch (_: Exception) {
            }
        }
        track = null
        onDone = null
    }
}
