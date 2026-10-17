package com.shivtej.aj

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import androidx.core.content.ContextCompat

/**
 * एक फ़ोटो लेना (आगे या पीछे का camera), बिना किसी preview स्क्रीन के।
 * Android खुद status bar में हरा camera निशान दिखाता है; साथ में CaptureService notification और shutter आवाज़ भी चलती है।
 */
object CameraCapture {

    /** front=true तो सामने (selfie) camera; result में JPEG bytes या null */
    fun capture(ctx: Context, front: Boolean, result: (ByteArray?) -> Unit) {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            result(null); return
        }
        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val wantFacing = if (front) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
        val cameraId = try {
            cm.cameraIdList.firstOrNull {
                cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == wantFacing
            } ?: cm.cameraIdList.firstOrNull()
        } catch (e: Exception) {
            null
        }
        if (cameraId == null) {
            result(null); return
        }

        val thread = HandlerThread("aj-cam").apply { start() }
        val handler = Handler(thread.looper)
        var finished = false
        var camera: CameraDevice? = null

        fun finish(bytes: ByteArray?) {
            if (finished) return
            finished = true
            try {
                camera?.close()
            } catch (_: Exception) {
            }
            try {
                thread.quitSafely()
            } catch (_: Exception) {
            }
            result(bytes)
        }

        // बहुत देर हो तो छोड़ दो
        handler.postDelayed({ finish(null) }, 8000)

        try {
            val chars = cm.getCameraCharacteristics(cameraId)
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val sizes = map?.getOutputSizes(ImageFormat.JPEG)
            val size = sizes?.filter { it.width <= 1920 && it.height <= 1920 }?.maxByOrNull { it.width.toLong() * it.height }
                ?: sizes?.minByOrNull { it.width.toLong() * it.height }
            val w = size?.width ?: 1280
            val h = size?.height ?: 720

            val reader = ImageReader.newInstance(w, h, ImageFormat.JPEG, 1)
            reader.setOnImageAvailableListener({ r ->
                val img = try {
                    r.acquireLatestImage()
                } catch (e: Exception) {
                    null
                }
                val bytes = img?.let {
                    val buf = it.planes[0].buffer
                    val arr = ByteArray(buf.remaining())
                    buf.get(arr)
                    it.close()
                    arr
                }
                try {
                    reader.close()
                } catch (_: Exception) {
                }
                finish(bytes)
            }, handler)

            val sensorRotation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0

            cm.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    camera = device
                    try {
                        device.createCaptureSession(listOf(reader.surface), object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(session: CameraCaptureSession) {
                                try {
                                    val req = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                                    req.addTarget(reader.surface)
                                    req.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                                    req.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
                                    req.set(CaptureRequest.JPEG_ORIENTATION, sensorRotation)
                                    // थोड़ा रुककर capture, ताकि auto-exposure सेट हो जाए
                                    handler.postDelayed({
                                        try {
                                            session.capture(req.build(), null, handler)
                                        } catch (e: Exception) {
                                            finish(null)
                                        }
                                    }, 600)
                                } catch (e: Exception) {
                                    finish(null)
                                }
                            }

                            override fun onConfigureFailed(session: CameraCaptureSession) = finish(null)
                        }, handler)
                    } catch (e: Exception) {
                        finish(null)
                    }
                }

                override fun onDisconnected(device: CameraDevice) = finish(null)
                override fun onError(device: CameraDevice, error: Int) = finish(null)
            }, handler)
        } catch (e: Exception) {
            finish(null)
        }
    }
}
