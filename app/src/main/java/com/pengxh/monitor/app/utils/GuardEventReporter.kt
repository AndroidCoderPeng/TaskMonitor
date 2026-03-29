package com.pengxh.monitor.app.utils

import java.util.concurrent.atomic.AtomicReference

/**
 * 进程内事件上报器：CaptureImageService 将重要错误上报给 ForegroundRunningService，
 * 再由 ForegroundRunningService 通过 IGuardCallback 通知 B。
 *
 * 不做任何恢复，仅负责“可靠通知”。
 */
object GuardEventReporter {
    interface Reporter {
        fun notify(code: Int, message: String)
    }

    private val reporterRef = AtomicReference<Reporter?>(null)

    fun setReporter(reporter: Reporter) {
        reporterRef.set(reporter)
    }

    fun clearReporter() {
        reporterRef.set(null)
    }

    fun notify(code: Int, message: String) {
        reporterRef.get()?.notify(code, message)
    }
}