package com.shivtej.aj

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.widget.Toast
import androidx.core.content.ContextCompat

/**
 * Screen share की इजाज़त माँगने वाली छोटी (दिखती नहीं) screen।
 * Android हर बार "Allow" माँगता है — यह छुपाया नहीं जा सकता।
 */
class ScreenActivity : Activity() {

    private var sec = 120

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sec = intent.getIntExtra("sec", 120)
        try {
            val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            startActivityForResult(mpm.createScreenCaptureIntent(), 1)
        } catch (e: Exception) {
            finish()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1 && resultCode == RESULT_OK && data != null) {
            val i = Intent(this, ScreenService::class.java)
                .putExtra("code", resultCode)
                .putExtra("data", data)
                .putExtra("sec", sec)
            ContextCompat.startForegroundService(this, i)
        } else {
            Toast.makeText(this, "Screen share not allowed", Toast.LENGTH_SHORT).show()
        }
        finish()
    }
}
