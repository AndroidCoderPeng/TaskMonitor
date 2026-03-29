package com.pengxh.monitor.app.service

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import com.pengxh.monitor.app.databinding.FloatingWindowBinding
import kotlin.math.abs

class FloatingWindowService : Service() {

    private val kTag = "FloatingWindowService"
    private var isRunning = false
    private val windowManager by lazy { getSystemService(WindowManager::class.java) }
    private lateinit var binding: FloatingWindowBinding
    private var params: WindowManager.LayoutParams? = null
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var screenWidth = 0
    private var isDragging = false

    override fun onCreate() {
        super.onCreate()
        if (isRunning) {
            Log.d(kTag, "Service already running, skip creating floating window")
            return
        }
        isRunning = true

        binding = FloatingWindowBinding.inflate(LayoutInflater.from(this))

        val displayMetrics = resources.displayMetrics
        screenWidth = displayMetrics.widthPixels

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.START or Gravity.TOP
        }.also {
            windowManager.addView(binding.root, it)
        }

        onDragMove()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun onDragMove() {
        binding.root.setOnTouchListener(object : View.OnTouchListener {
            override fun onTouch(v: View?, event: MotionEvent?): Boolean {
                when (event?.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params?.x ?: 0
                        initialY = params?.y ?: 0
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        isDragging = false
                        return false
                    }

                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - initialTouchX).toInt()
                        val dy = (event.rawY - initialTouchY).toInt()
                        if (abs(dx) > 10 || abs(dy) > 10) {
                            isDragging = true
                        }

                        params?.let {
                            it.x = initialX + (event.rawX - initialTouchX).toInt()
                            it.y = initialY + (event.rawY - initialTouchY).toInt()
                            windowManager.updateViewLayout(binding.root, it)
                        }
                        return false
                    }

                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (isDragging) {
                            autoStickToEdge()
                        }
                        return false
                    }

                    else -> return false
                }
            }
        })
    }

    private fun autoStickToEdge() {
        params?.let { layoutParams ->
            val currentX = layoutParams.x
            val windowWidth = binding.root.width
            val centerX = currentX + windowWidth / 2
            val targetX = if (centerX < screenWidth / 2) {
                0
            } else {
                screenWidth - windowWidth
            }

            val animator = ValueAnimator.ofInt(currentX, targetX).apply {
                duration = 250
                interpolator = DecelerateInterpolator()
                addUpdateListener { animation ->
                    layoutParams.x = animation.animatedValue as Int
                    try {
                        windowManager.updateViewLayout(binding.root, layoutParams)
                    } catch (e: IllegalArgumentException) {
                        Log.w(kTag, "View not attached to window manager", e)
                    }
                }
            }
            animator.start()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        if (::binding.isInitialized && binding.root.isAttachedToWindow) {
            try {
                windowManager.removeViewImmediate(binding.root)
            } catch (e: IllegalArgumentException) {
                Log.w(kTag, "View not attached to window manager", e)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}