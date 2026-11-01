package com.kulzzy.screenrecorder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.DisplayMetrics
import android.view.WindowManager
import android.widget.Toast
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RecorderService : Service() {

    companion object {
        const val ACTION_START = "com.kulzzy.screenrecorder.START"
        const val ACTION_STOP = "com.kulzzy.screenrecorder.STOP"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"

        private const val CHANNEL_ID = "kulzzy_recording_channel"
        private const val NOTIFICATION_ID = 4101
    }

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var recorder: MediaRecorder? = null
    private var outputUri: Uri? = null
    private var outputDescriptor: ParcelFileDescriptor? = null

    private var recording = false
    private var finishing = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopRecording()
                return START_NOT_STICKY
            }

            ACTION_START -> {
                if (recording) return START_NOT_STICKY

                try {
                    createNotificationChannel()
                    startRecordingForeground()

                    val resultCode = intent.getIntExtra(
                        EXTRA_RESULT_CODE,
                        0
                    )

                    val resultData = if (Build.VERSION.SDK_INT >= 33) {
                        intent.getParcelableExtra(
                            EXTRA_RESULT_DATA,
                            Intent::class.java
                        )
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra<Intent>(
                            EXTRA_RESULT_DATA
                        )
                    }

                    if (resultCode != RESULT_OK || resultData == null) {
                        throw IllegalStateException(
                            "Screen recording permission was not received."
                        )
                    }

                    beginRecording(resultCode, resultData)
                } catch (e: Exception) {
                    Toast.makeText(
                        this,
                        "Recording failed: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()

                    stopRecording()
                }
            }
        }

        return START_NOT_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Kulzzy Screen Recording",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows when screen recording is active"
            }

            val manager = getSystemService(
                NotificationManager::class.java
            )

            manager.createNotificationChannel(channel)
        }
    }

    private fun startRecordingForeground() {
        val openAppIntent = Intent(this, MainActivity::class.java)

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or
                PendingIntent.FLAG_IMMUTABLE
        )

        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }.setContentTitle("Kulzzy Screen Recorder")
            .setContentText("Screen recording is running")
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun beginRecording(
        resultCode: Int,
        resultData: Intent
    ) {
        val manager = getSystemService(
            Context.MEDIA_PROJECTION_SERVICE
        ) as MediaProjectionManager

        projection = manager.getMediaProjection(
            resultCode,
            resultData
        ) ?: throw IllegalStateException(
            "Android could not start screen capture."
        )

        val metrics = getScreenMetrics()
        var width = metrics.widthPixels
        var height = metrics.heightPixels

        // H.264 encoders commonly require even dimensions.
        width -= width % 2
        height -= height % 2

        if (width < 2 || height < 2) {
            throw IllegalStateException("Invalid screen dimensions.")
        }

        val date = SimpleDateFormat(
            "yyyyMMdd_HHmmss",
            Locale.US
        ).format(Date())

        val fileName = "Kulzzy_Recording_$date.mp4"

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    "Movies/KulzzyScreenRecorder"
                )
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }

        outputUri = contentResolver.insert(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            values
        ) ?: throw IllegalStateException(
            "Could not create the video file."
        )

        outputDescriptor = contentResolver.openFileDescriptor(
            outputUri!!,
            "w"
        ) ?: throw IllegalStateException(
            "Could not open the video file."
        )

        val videoRecorder = if (Build.VERSION.SDK_INT >= 31) {
            MediaRecorder(this)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

        recorder = videoRecorder

        videoRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE)
        videoRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        videoRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264)

        videoRecorder.setVideoSize(width, height)
        videoRecorder.setVideoFrameRate(30)

        val estimatedBitrate = (width.toLong() * height * 30L / 6L)
            .coerceIn(4_000_000L, 24_000_000L)
            .toInt()

        videoRecorder.setVideoEncodingBitRate(estimatedBitrate)
        videoRecorder.setOutputFile(outputDescriptor!!.fileDescriptor)
        videoRecorder.prepare()

        projection?.registerCallback(
            object : MediaProjection.Callback() {
                override fun onStop() {
                    stopRecording()
                }
            },
            null
        )

        virtualDisplay = projection?.createVirtualDisplay(
            "KulzzyScreenRecorder",
            width,
            height,
            metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            videoRecorder.surface,
            null,
            null
        ) ?: throw IllegalStateException(
            "Could not capture the screen."
        )

        videoRecorder.start()
        recording = true
        finishing = false
    }

    private fun getScreenMetrics(): DisplayMetrics {
        val metrics = DisplayMetrics()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val display = display
            if (display != null) {
                display.getRealMetrics(metrics)
            } else {
                @Suppress("DEPRECATION")
                (getSystemService(Context.WINDOW_SERVICE) as WindowManager)
                    .defaultDisplay.getRealMetrics(metrics)
            }
        } else {
            @Suppress("DEPRECATION")
            (getSystemService(Context.WINDOW_SERVICE) as WindowManager)
                .defaultDisplay.getRealMetrics(metrics)
        }

        return metrics
    }

    private fun stopRecording() {
        if (finishing) return
        finishing = true

        var savedSuccessfully = false

        try {
            if (recording) {
                recorder?.stop()
                savedSuccessfully = true
            }
        } catch (_: Exception) {
            savedSuccessfully = false
        }

        recording = false

        try {
            virtualDisplay?.release()
        } catch (_: Exception) {
        }
        virtualDisplay = null

        try {
            recorder?.reset()
            recorder?.release()
        } catch (_: Exception) {
        }
        recorder = null

        try {
            projection?.stop()
        } catch (_: Exception) {
        }
        projection = null

        try {
            outputDescriptor?.close()
        } catch (_: Exception) {
        }
        outputDescriptor = null

        outputUri?.let { uri ->
            try {
                if (savedSuccessfully) {
                    val values = ContentValues()

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    }

                    contentResolver.update(uri, values, null, null)
                } else {
                    contentResolver.delete(uri, null, null)
                }
            } catch (_: Exception) {
            }
        }

        outputUri = null

        val notificationManager = getSystemService(
            Context.NOTIFICATION_SERVICE
        ) as NotificationManager

        notificationManager.cancel(NOTIFICATION_ID)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (!finishing) {
            stopRecording()
        }
        super.onDestroy()
    }
}
