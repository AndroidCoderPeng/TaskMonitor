package com.pengxh.monitor.app.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.os.IBinder
import android.os.RemoteCallbackList
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.graphics.createBitmap
import com.pengxh.kt.lite.extensions.createImageFileDir
import com.pengxh.kt.lite.extensions.saveImage
import com.pengxh.monitor.app.ICaptureCallback
import com.pengxh.monitor.app.ICaptureService
import com.pengxh.monitor.app.R
import com.pengxh.monitor.app.utils.ProjectionSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class CaptureImageService : Service(), CoroutineScope by MainScope() {

    companion object {
        const val EVENT_CAPTURE_EXCEPTION = 2001
        const val EVENT_CAPTURE_NOT_ACTIVE = 2002
        const val EVENT_CAPTURE_IMAGE_NULL = 2003
    }

    private val kTag = "CaptureImageService"
    private val notificationManager by lazy { getSystemService(NotificationManager::class.java) }
    private val dateTimeFormat by lazy { SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA) }

    // 截屏间隔 5 秒
    private val captureInterval = 5000L
    private var captureJob: kotlinx.coroutines.Job? = null

    private val callbacks = RemoteCallbackList<ICaptureCallback>()
    private val latestPath = AtomicReference<String?>(null)
    private val latestWallTime = AtomicLong(0L)

    private val binder = object : ICaptureService.Stub() {
        override fun registerCallback(cb: ICaptureCallback?) {
            if (cb != null) callbacks.register(cb)
        }

        override fun unregisterCallback(cb: ICaptureCallback?) {
            if (cb != null) callbacks.unregister(cb)
        }

        override fun getLatestCapturePath(): String? = latestPath.get()

        override fun getLatestCaptureWallTimeMs(): Long = latestWallTime.get()
    }

    override fun onCreate() {
        super.onCreate()
        val name = resources.getString(R.string.app_name)
        val channel = NotificationChannel(
            "capture_image_service_channel",
            "${name}前台服务",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Channel for Capture Image Service"
        }
        notificationManager.createNotificationChannel(channel)
        val notificationBuilder =
            NotificationCompat.Builder(this, "capture_image_service_channel").apply {
                setPriority(NotificationCompat.PRIORITY_LOW) // 设置通知优先级
                setOngoing(true)
                setSilent(true)
                setSound(null) // 禁用声音
                setVibrate(null) // 禁用振动
            }
        val notification = notificationBuilder.build()
        startForeground(1001, notification)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startPeriodicCapture()
        return START_STICKY
    }

    private fun startPeriodicCapture() {
        captureJob?.cancel()
        captureJob = launch {
            while (isActive) {
                val now = System.currentTimeMillis()

                if (ProjectionSession.state == ProjectionSession.State.ACTIVE && ProjectionSession.getProjection() != null) {
                    runCatching {
                        val path = captureOnce()
                        latestPath.set(path)
                        latestWallTime.set(System.currentTimeMillis())
                        notifySuccess(path, System.currentTimeMillis())
                    }.onFailure { e ->
                        Log.e(kTag, "capture failed", e)
                        notifyError(EVENT_CAPTURE_EXCEPTION, e.message ?: "截图失败", now)
                    }
                } else {
                    Log.w(kTag, "MediaProjection not active, skipping capture")
                    notifyError(EVENT_CAPTURE_NOT_ACTIVE, "截图服务未启动", now)
                }
                delay(captureInterval)
            }
        }
    }

    private suspend fun captureOnce(): String = withContext(Dispatchers.IO) {
        val projection = ProjectionSession.getProjection()
        if (projection == null || ProjectionSession.state != ProjectionSession.State.ACTIVE) {
            throw IllegalStateException("MediaProjection not active. state=${ProjectionSession.state}")
        }

        val metrics = resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val densityDpi = metrics.densityDpi

        Log.d(kTag, "captureOnce: width=$width, height=$height, dpi=$densityDpi")
        val imagePath = "${createImageFileDir()}/${dateTimeFormat.format(Date())}.png"

        val imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 1)

        val flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR or
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC

        var virtualDisplay: VirtualDisplay? = null
        try {
            virtualDisplay = projection.createVirtualDisplay(
                "CaptureImageDisplay",
                width,
                height,
                densityDpi,
                flags,
                imageReader.surface,
                null,
                null
            )
            //必须延迟一下，因为生出图片需要时间缓冲，不能秒得
            delay(1000)
            withContext(Dispatchers.IO) {
                val image = imageReader.acquireNextImage()
                if (image == null) {
                    Log.e(kTag, "acquireLatestImage returned null")
                    notifyError(EVENT_CAPTURE_IMAGE_NULL, "截图失败", System.currentTimeMillis())
                    return@withContext
                }
                val width = image.width
                val height = image.height
                val planes = image.planes
                val buffer = planes[0].buffer
                val pixelStride = planes[0].pixelStride
                val rowStride = planes[0].rowStride
                val rowPadding = rowStride - pixelStride * width
                val bitmap = createBitmap(width + rowPadding / pixelStride, height)
                bitmap.copyPixelsFromBuffer(buffer)

                image.close()

                try {
                    virtualDisplay?.release()
                } catch (t: Throwable) {
                    t.printStackTrace()
                }

                try {
                    imageReader.close()
                } catch (t: Throwable) {
                    t.printStackTrace()
                }

                Log.d(kTag, "startCaptureScreen: 完成截屏")
                bitmap.saveImage(imagePath)
            }
        } finally {
            try {
                virtualDisplay?.release()
            } catch (t: Throwable) {
                t.printStackTrace()
            }

            try {
                imageReader.close()
            } catch (t: Throwable) {
                t.printStackTrace()
            }
        }
        imagePath
    }

    private fun notifySuccess(path: String, wallTimeMs: Long) {
        val n = callbacks.beginBroadcast()
        for (i in 0 until n) {
            runCatching { callbacks.getBroadcastItem(i).onCaptureSuccess(path, wallTimeMs) }
        }
        callbacks.finishBroadcast()
    }

    private fun notifyError(code: Int, msg: String, wallTimeMs: Long) {
        val n = callbacks.beginBroadcast()
        for (i in 0 until n) {
            runCatching { callbacks.getBroadcastItem(i).onCaptureError(code, msg, wallTimeMs) }
        }
        callbacks.finishBroadcast()
    }

    override fun onDestroy() {
        super.onDestroy()
        captureJob?.cancel()
        callbacks.kill()
        cancel()
    }

    override fun onBind(intent: Intent?): IBinder = binder
}