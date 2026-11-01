package com.kulzzy.screenrecorder

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.graphics.Color
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var statusText: TextView

    companion object {
        private const val REQUEST_SCREEN_CAPTURE = 1001
        private const val NAVY = 0xFF06122A.toInt()
        private const val GOLD = 0xFFFFD700.toInt()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createInterface()
    }

    private fun createInterface() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(28, 35, 28, 35)
            setBackgroundColor(NAVY)
        }

        val title = TextView(this).apply {
            text = "KULZZY"
            textSize = 34f
            setTextColor(GOLD)
            gravity = Gravity.CENTER
        }

        val subtitle = TextView(this).apply {
            text = "4K SCREEN RECORDER"
            textSize = 20f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }

        statusText = TextView(this).apply {
            text = "Ready to record"
            textSize = 16f
            setTextColor(GOLD)
            gravity = Gravity.CENTER
            setPadding(0, 30, 0, 30)
        }

        val startButton = Button(this).apply {
            text = "START SCREEN RECORDING"
            setTextColor(NAVY)
            setBackgroundColor(GOLD)
            setOnClickListener { requestScreenCapture() }
        }

        val stopButton = Button(this).apply {
            text = "STOP RECORDING"
            setOnClickListener {
                val intent = Intent(
                    this@MainActivity,
                    RecorderService::class.java
                ).setAction(RecorderService.ACTION_STOP)

                startService(intent)
                statusText.text = "Stopping recording..."
            }
        }

        val info = TextView(this).apply {
            text = "Records your screen to MP4 video.\n" +
                "Maximum resolution depends on your device.\n" +
                "Find saved recordings in Movies/KulzzyScreenRecorder."
            textSize = 13f
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER
            setPadding(0, 25, 0, 0)
        }

        root.addView(title, matchWidth())
        root.addView(subtitle, matchWidth())
        root.addView(statusText, matchWidth())
        root.addView(startButton, matchWidth())
        root.addView(stopButton, matchWidth())
        root.addView(info, matchWidth())

        setContentView(root)
    }

    private fun matchWidth(): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            bottomMargin = 12
        }
    }

    private fun requestScreenCapture() {
        try {
            val manager = getSystemService(
                Context.MEDIA_PROJECTION_SERVICE
            ) as MediaProjectionManager

            @Suppress("DEPRECATION")
            startActivityForResult(
                manager.createScreenCaptureIntent(),
                REQUEST_SCREEN_CAPTURE
            )
        } catch (e: Exception) {
            Toast.makeText(
                this,
                "Unable to start screen capture: ${e.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    @Deprecated("Uses Android activity result callback for broad compatibility")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == REQUEST_SCREEN_CAPTURE) {
            if (resultCode == RESULT_OK && data != null) {
                val intent = Intent(
                    this,
                    RecorderService::class.java
                ).apply {
                    action = RecorderService.ACTION_START
                    putExtra(RecorderService.EXTRA_RESULT_CODE, resultCode)
                    putExtra(RecorderService.EXTRA_RESULT_DATA, data)
                }

                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(intent)
                    } else {
                        startService(intent)
                    }
                    statusText.text = "Starting screen recording..."
                } catch (e: Exception) {
                    statusText.text = "Could not start recording"
                    Toast.makeText(
                        this,
                        "Error: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } else {
                statusText.text = "Screen recording permission cancelled"
            }
        }
    }
}
