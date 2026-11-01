package com.shivtej.aj

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import androidx.core.content.ContextCompat
import java.io.File

/**
 * दूसरे फ़ोन के कहने पर video (camera + mic) record करना — बिना किसी preview screen के।
 * Android खुद status bar में हरा camera/mic निशान दिखाता है; साथ में CaptureService का notification भी।
 */
object VideoCapture {

    fun record(ctx: Context, front: Boolean, sec: Int, outFile: File, done: (Boolean) -> Unit) {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) { done(false); return }
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { done(false); return }
        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val facing = if (front) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
        val camId = try {
            cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == facing } ?: cm.cameraIdList.firstOrNull()
        } catch (e: Exception) { null }
        if (camId == null) { done(false); return }

        val thread = HandlerThread("aj-vid").apply { start() }
        val handler = Handler(thread.looper)
        var finished = false
        var camera: CameraDevice? = null
        var session: CameraCaptureSession? = null
        var recorder: MediaRecorder? = null

        fun fail() {
            if (finished) return
            finished = true
            try { recorder?.release() } catch (_: Exception) {}
            try { session?.close() } catch (_: Exception) {}
            try { camera?.close() } catch (_: Exception) {}
            try { thread.quitSafely() } catch (_: Exception) {}
            done(false)
        }

        val chars = try { cm.getCameraCharacteristics(camId) } catch (e: Exception) { fail(); return }
        val sensorRotation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0

        val rec = try {
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(ctx) else @Suppress("DEPRECATION") MediaRecorder()).apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setVideoSize(1280, 720)
                setVideoFrameRate(30)
                setVideoEncodingBitRate(3_000_000)
                setAudioEncodingBitRate(96_000)
                setAudioSamplingRate(44100)
                setOrientationHint(if (front) (360 - sensorRotation) % 360 else sensorRotation)
                setOutputFile(outFile.absolutePath)
                prepare()
            }
        } catch (e: Exception) { fail(); return }
        recorder = rec
        val surface = rec.surface

        // safety timeout (रिकॉर्डिंग अटक जाए तो)
        handler.postDelayed({ fail() }, (sec + 20) * 1000L)

        try {
            cm.openCamera(camId, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    camera = device
                    try {
                        device.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(s: CameraCaptureSession) {
                                session = s
                                try {
                                    val req = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                                    req.addTarget(surface)
                                    s.setRepeatingRequest(req.build(), null, handler)
                                    rec.start()
                                    handler.postDelayed({
                                        if (finished) return@postDelayed
                                        finished = true
                                        var ok = true
                                        try { s.stopRepeating() } catch (_: Exception) {}
                                        try { rec.stop() } catch (_: Exception) { ok = false }
                                        try { rec.release() } catch (_: Exception) {}
                                        try { s.close() } catch (_: Exception) {}
                                        try { device.close() } catch (_: Exception) {}
                                        try { thread.quitSafely() } catch (_: Exception) {}
                                        done(ok && outFile.exists() && outFile.length() > 0)
                                    }, sec * 1000L)
                                } catch (e: Exception) { fail() }
                            }
                            override fun onConfigureFailed(s: CameraCaptureSession) { fail() }
                        }, handler)
                    } catch (e: Exception) { fail() }
                }
                override fun onDisconnected(device: CameraDevice) { fail() }
                override fun onError(device: CameraDevice, error: Int) { fail() }
            }, handler)
        } catch (e: Exception) { fail() }
    }
}
