package com.pengxh.monitor.app.service

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.os.RemoteCallbackList
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import com.pengxh.monitor.app.IGuardCallback
import com.pengxh.monitor.app.IGuardService
import com.pengxh.monitor.app.R
import com.pengxh.monitor.app.utils.GuardEventReporter
import com.pengxh.monitor.app.utils.ProjectionSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.cancellation.CancellationException

/**
 * APP前台服务，降低APP被系统杀死的可能性
 * */
class ForegroundRunningService : Service(), CoroutineScope by MainScope() {

    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data"

        const val NOTIFICATION_CODE_NEED_AUTH = 1
        const val NOTIFICATION_CODE_CAPTURE_FAILED = 2
        const val NOTIFICATION_CODE_HEARTBEAT_TIMEOUT = 3
    }

    private val kTag = "ForegroundService"
    private val notificationManager by lazy { getSystemService(NotificationManager::class.java) }
    private val mgr by lazy { getSystemService(MediaProjectionManager::class.java) }
    private val callbacks = RemoteCallbackList<IGuardCallback>()

    // ===== 心跳状态（A侧保存 B→A 心跳）=====
    private val lastBeatElapsedMs = AtomicLong(0L)
    private val lastBeatStatusCode = AtomicInteger(0)
    private val lastBeatStatusMsg = AtomicReference("")

    // 超时阈值写死：60s 心跳，3min 超时
    private val heartbeatTimeoutMs = 3 * 60_000L

    private val binder = object : IGuardService.Stub() {
        override fun beat(
            from: String?,
            elapsedRealtimeMs: Long,
            statusCode: Int,
            statusMsg: String?
        ) {
            val now = SystemClock.elapsedRealtime()
            // 容错：对端如果没传 elapsedRealtime，也至少用本机 now
            val beatTs = if (elapsedRealtimeMs > 0) elapsedRealtimeMs else now

            lastBeatElapsedMs.set(beatTs)
            lastBeatStatusCode.set(statusCode)
            lastBeatStatusMsg.set(statusMsg ?: "")

            Log.d(kTag, "beat from=$from ts=$beatTs code=$statusCode msg=${statusMsg ?: ""}")
        }

        override fun getLastBeatFromPeerElapsedMs(): Long = lastBeatElapsedMs.get()

        override fun getLastLocalElapsedMs(): Long = SystemClock.elapsedRealtime()

        override fun getHealthCode(): Int {
            return when (ProjectionSession.state) {
                ProjectionSession.State.ACTIVE -> 0
                ProjectionSession.State.NEED_AUTH -> 1
                ProjectionSession.State.IDLE -> 2
            }
        }

        override fun registerCallback(cb: IGuardCallback?) {
            if (cb != null) callbacks.register(cb)
        }

        override fun unregisterCallback(cb: IGuardCallback?) {
            if (cb != null) callbacks.unregister(cb)
        }
    }

    override fun onCreate() {
        super.onCreate()
        val name = resources.getString(R.string.app_name)
        val channel = NotificationChannel(
            "foreground_running_service_channel",
            "${name}前台服务",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Channel for Foreground Running Service"
        }
        notificationManager.createNotificationChannel(channel)
        val notificationBuilder =
            NotificationCompat.Builder(this, "foreground_running_service_channel").apply {
                setSmallIcon(R.mipmap.ic_launcher)
                setContentText("为保证${name}正常运行，请勿移除此通知")
                setPriority(NotificationCompat.PRIORITY_LOW) // 设置通知优先级
                setOngoing(true)
                setOnlyAlertOnce(true)
                setSilent(true)
                setCategory(NotificationCompat.CATEGORY_SERVICE)
                setShowWhen(true)
                setSound(null) // 禁用声音
                setVibrate(null) // 禁用振动
            }
        val notification = notificationBuilder.build()
        startForeground(1001, notification)

        // 注册进程内事件转发器
        GuardEventReporter.setReporter(object : GuardEventReporter.Reporter {
            override fun notify(code: Int, message: String) {
                notifyPeer(code, message)
            }
        })

        // 每 60 秒检查一次
        launch {
            while (isActive) {
                try {
                    delay(60_000L)
                    val last = lastBeatElapsedMs.get()
                    val now = SystemClock.elapsedRealtime()
                    if (last > 0 && now - last > heartbeatTimeoutMs) {
                        notifyPeer(NOTIFICATION_CODE_HEARTBEAT_TIMEOUT, "设备心跳超时")
                    }
                } catch (t: CancellationException) {
                    // 协程被取消时正常退出
                    throw t
                } catch (t: Throwable) {
                    Log.e(kTag, "Heartbeat self-check error", t)
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, -1) ?: -1
        val data = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            intent?.getParcelableExtra<Intent>(EXTRA_DATA)
        }

        if (ProjectionSession.state == ProjectionSession.State.ACTIVE) {
            Log.d(kTag, "MediaProjection already active, skipping creation")
            return START_STICKY
        }

        if (resultCode == Activity.RESULT_OK && data != null) {
            createMediaProjection(resultCode, data)
        } else {
            Log.w(kTag, "No valid projection data, service running without MediaProjection")
            notifyPeer(NOTIFICATION_CODE_NEED_AUTH, "屏幕投屏未授权，请重新授权")
        }

        return START_STICKY
    }

    private fun createMediaProjection(resultCode: Int, data: Intent) {
        try {
            val projection = mgr.getMediaProjection(resultCode, data)
            if (projection == null) {
                Log.e(kTag, "Failed to create MediaProjection")
                notifyPeer(NOTIFICATION_CODE_CAPTURE_FAILED, "MediaProjection 创建失败")
                return
            }

            projection.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    super.onStop()
                    Log.d(kTag, "MediaProjection stopped")
                    ProjectionSession.markStoppedNeedAuth()
                    notifyPeer(NOTIFICATION_CODE_NEED_AUTH, "投屏会话已中断，需要重新授权")
                }
            }, null)

            ProjectionSession.setProjection(projection)
            Log.d(kTag, "MediaProjection created successfully")

            // 启动截图服务
            Intent(this, CaptureImageService::class.java).apply {
                startForegroundService(this)
            }
        } catch (e: Exception) {
            Log.e(kTag, "Error creating MediaProjection", e)
            notifyPeer(NOTIFICATION_CODE_CAPTURE_FAILED, "屏幕投屏失败: ${e.message}")
        }
    }

    // 添加辅助方法
    private fun notifyPeer(code: Int, message: String) {
        val n = callbacks.beginBroadcast()
        for (i in 0 until n) {
            runCatching {
                callbacks.getBroadcastItem(i)
                    .onNotification(code, message, System.currentTimeMillis())
            }.onFailure { e ->
                Log.e(kTag, "Failed to notify peer", e)
            }
        }
        callbacks.finishBroadcast()
    }

    override fun onDestroy() {
        super.onDestroy()
        callbacks.kill()
        cancel()
        GuardEventReporter.clearReporter()
        ProjectionSession.clear()
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onBind(intent: Intent?): IBinder = binder
}