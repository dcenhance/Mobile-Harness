package com.jarves.mh.runtime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.jarves.mh.ui.AppStrings
import java.io.File
import java.io.FileOutputStream

/**
 * One screen frame per request, written into the chat's attachment folder.
 *
 * The capture needs a foreground service of type `mediaProjection` for the whole lifetime of the
 * projection: Android 14 refuses `getMediaProjection` outside one, and the projection dies with the
 * process either way. The system consent dialog stays the owner's decision — this service is only
 * started with the token that dialog returned.
 *
 * One shot per invocation keeps it honest: nothing runs in the background after the frame is written,
 * so there is no idle session to leak. A screen that is off yields no frame and reports that instead
 * of writing an empty file.
 */
class ScreenCaptureService : Service() {

    companion object {
        const val ACTION_CAPTURE = "com.jarves.mh.action.CAPTURE_SCREEN"
        const val ACTION_STOP = "com.jarves.mh.action.STOP_CAPTURE"

        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"
        const val EXTRA_TARGET_PATH = "targetPath"
        const val EXTRA_RELATIVE_PATH = "relativePath"
        const val EXTRA_FILE_NAME = "fileName"

        private const val CHANNEL_ID = "mobileharness-screen-capture"
        private const val NOTIFICATION_ID = 7411
        private const val FRAME_TIMEOUT_MS = 12_000L

        fun intent(
            context: Context,
            resultCode: Int,
            resultData: Intent,
            targetPath: String,
            relativePath: String,
            fileName: String,
        ): Intent = Intent(context, ScreenCaptureService::class.java).apply {
            action = ACTION_CAPTURE
            putExtra(EXTRA_RESULT_CODE, resultCode)
            putExtra(EXTRA_RESULT_DATA, resultData)
            putExtra(EXTRA_TARGET_PATH, targetPath)
            putExtra(EXTRA_RELATIVE_PATH, relativePath)
            putExtra(EXTRA_FILE_NAME, fileName)
        }
    }

    private var worker: HandlerThread? = null
    private var workerHandler: Handler? = null
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var finished = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            finish(null, ScreenFrameFiles.Refusal.DENIED)
            return START_NOT_STICKY
        }
        startForegroundCapture()
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        @Suppress("DEPRECATION")
        val resultData = intent?.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
        val target = intent?.getStringExtra(EXTRA_TARGET_PATH)
        val relativePath = intent?.getStringExtra(EXTRA_RELATIVE_PATH)
        val fileName = intent?.getStringExtra(EXTRA_FILE_NAME)
        if (resultData == null || target.isNullOrBlank() || relativePath.isNullOrBlank() || fileName.isNullOrBlank()) {
            finish(null, ScreenFrameFiles.Refusal.NO_TARGET)
            return START_NOT_STICKY
        }
        val thread = HandlerThread("mh-screen-capture").apply { start() }
        worker = thread
        val handler = Handler(thread.looper)
        workerHandler = handler
        handler.postDelayed({ finish(null, ScreenFrameFiles.Refusal.NO_FRAME) }, FRAME_TIMEOUT_MS)
        handler.post { capture(resultCode, resultData, File(target), relativePath, fileName) }
        return START_NOT_STICKY
    }

    private fun startForegroundCapture() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val label = AppStrings.get(this, com.jarves.mh.R.string.screen_capture_channel)
        val description = AppStrings.get(this, com.jarves.mh.R.string.screen_capture_channel_description)
        val channel = NotificationChannel(CHANNEL_ID, label, NotificationManager.IMPORTANCE_LOW)
        channel.description = description
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val stop = PendingIntent.getService(
            this,
            0,
            Intent(this, ScreenCaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(AppStrings.get(this, com.jarves.mh.R.string.screen_capture_title))
            .setContentText(AppStrings.get(this, com.jarves.mh.R.string.screen_capture_running))
            .setSmallIcon(com.jarves.mh.R.drawable.ic_notification)
            .setOngoing(true)
            .addAction(0, AppStrings.get(this, com.jarves.mh.R.string.screen_capture_stop), stop)
            .build()
    }

    private fun capture(resultCode: Int, resultData: Intent, target: File, relativePath: String, fileName: String) {
        val manager = getSystemService(MediaProjectionManager::class.java)
        if (manager == null) {
            finish(null, ScreenFrameFiles.Refusal.UNSUPPORTED)
            return
        }
        val active = runCatching { manager.getMediaProjection(resultCode, resultData) }.getOrNull()
        if (active == null) {
            finish(null, ScreenFrameFiles.Refusal.DENIED)
            return
        }
        projection = active
        active.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                if (!finished) finish(null, ScreenFrameFiles.Refusal.DENIED)
            }
        }, Handler(Looper.getMainLooper()))

        val metrics = resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        if (width <= 0 || height <= 0) {
            finish(null, ScreenFrameFiles.Refusal.UNSUPPORTED)
            return
        }
        val imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        reader = imageReader
        imageReader.setOnImageAvailableListener({ source ->
            val image = runCatching { source.acquireLatestImage() }.getOrNull()
            if (image == null) {
                finish(null, ScreenFrameFiles.Refusal.NO_FRAME)
                return@setOnImageAvailableListener
            }
            val bytes = runCatching { writeFrame(image, target) }.getOrNull()
            image.close()
            if (bytes == null) {
                finish(null, ScreenFrameFiles.Refusal.WRITE_FAILED)
            } else {
                finish(
                    ScreenCaptureResult.Frame(
                        fileName = fileName,
                        relativePath = relativePath,
                        bytes = bytes,
                        capturedAtMillis = System.currentTimeMillis(),
                    ),
                    null,
                )
            }
        }, workerHandler)

        val display = runCatching {
            active.createVirtualDisplay(
                "mobileharness-screen-capture",
                width,
                height,
                metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.surface,
                null,
                workerHandler,
            )
        }.getOrNull()
        if (display == null) {
            finish(null, ScreenFrameFiles.Refusal.UNSUPPORTED)
            return
        }
        virtualDisplay = display
    }

    /** Writes the frame as PNG and re-reads its header: what is written must really be a picture. */
    private fun writeFrame(image: Image, target: File): Long? {
        val bitmap = runCatching { toBitmap(image) }.getOrNull() ?: return null
        return try {
            target.parentFile?.mkdirs()
            FileOutputStream(target).use { stream -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream) }
        } finally {
            bitmap.recycle()
        }.let { written ->
            if (!written) return null
            val length = target.length()
            val header = ByteArray(8)
            val read = target.inputStream().use { it.read(header) }
            if (!ScreenFrameFiles.isPlausibleFrame(length, if (read == 8) header else null)) {
                target.delete()
                null
            } else {
                length
            }
        }
    }

    private fun toBitmap(image: Image): Bitmap {
        val plane = image.planes[0]
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * image.width
        val padded = Bitmap.createBitmap(
            image.width + if (pixelStride > 0) rowPadding / pixelStride else 0,
            image.height,
            Bitmap.Config.ARGB_8888,
        )
        padded.copyPixelsFromBuffer(plane.buffer)
        val cropped = Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
        if (cropped !== padded) padded.recycle()
        return cropped
    }

    /** Ends the capture exactly once: releases the projection and reports either frame or reason. */
    private fun finish(frame: ScreenCaptureResult.Frame?, refusal: ScreenFrameFiles.Refusal?) {
        if (finished) return
        finished = true
        runCatching { reader?.setOnImageAvailableListener(null, null) }
        runCatching { reader?.close() }
        reader = null
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        runCatching { projection?.stop() }
        projection = null
        workerHandler?.removeCallbacksAndMessages(null)
        ScreenCaptureResult.publish(
            if (frame != null) ScreenCaptureResult.Outcome.Captured(frame)
            else ScreenCaptureResult.Outcome.Refused(refusal ?: ScreenFrameFiles.Refusal.NO_FRAME),
        )
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE)
            else @Suppress("DEPRECATION") stopForeground(true)
        }
        stopSelf()
        worker?.quitSafely()
        worker = null
        workerHandler = null
    }

    override fun onDestroy() {
        if (!finished) finish(null, ScreenFrameFiles.Refusal.DENIED)
        super.onDestroy()
    }
}
