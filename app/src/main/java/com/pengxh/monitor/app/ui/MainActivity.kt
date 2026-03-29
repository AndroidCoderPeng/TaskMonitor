package com.pengxh.monitor.app.ui

import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.provider.Settings
import android.widget.RelativeLayout
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.pengxh.kt.lite.base.KotlinBaseActivity
import com.pengxh.kt.lite.extensions.show
import com.pengxh.monitor.app.R
import com.pengxh.monitor.app.databinding.ActivityMainBinding
import com.pengxh.monitor.app.service.FloatingWindowService
import com.pengxh.monitor.app.service.ForegroundRunningService

class MainActivity : KotlinBaseActivity<ActivityMainBinding>() {

    private val kTag = "MainActivity"
    private val floatingIntent by lazy { Intent(this, FloatingWindowService::class.java) }
    private val resultContract by lazy { ActivityResultContracts.StartActivityForResult() }
    private val projectionContract by lazy { ActivityResultContracts.StartActivityForResult() }
    private val mgr by lazy { getSystemService(MediaProjectionManager::class.java) }

    override fun initViewBinding(): ActivityMainBinding {
        return ActivityMainBinding.inflate(layoutInflater)
    }

    override fun setupTopBarLayout() {
        enableEdgeToEdge()
        val rootView = findViewById<RelativeLayout>(R.id.rootView)
        ViewCompat.setOnApplyWindowInsetsListener(rootView) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
    }

    override fun initOnCreate(savedInstanceState: Bundle?) {
        if (Settings.canDrawOverlays(this)) {
            startService(floatingIntent)
        } else {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
            overlayLauncher.launch(intent)
        }
    }

    private val overlayLauncher = registerForActivityResult(resultContract) {
        if (Settings.canDrawOverlays(this)) {
            startService(floatingIntent)
        }
    }

    private val projectionLauncher = registerForActivityResult(projectionContract) { result ->
        if (result.resultCode != RESULT_OK) {
            "用户拒绝授权".show(this)
            return@registerForActivityResult
        }

        val data = result.data ?: run {
            "授权失败".show(this)
            return@registerForActivityResult
        }

        // 启动前台服务并传递授权数据
        Intent(this, ForegroundRunningService::class.java).apply {
            putExtra(ForegroundRunningService.EXTRA_RESULT_CODE, result.resultCode)
            putExtra(ForegroundRunningService.EXTRA_DATA, data)
            startForegroundService(this)
        }

        finish()
    }

    override fun observeRequestState() {

    }

    override fun initEvent() {
        binding.startGuardButton.setOnClickListener {
            projectionLauncher.launch(mgr.createScreenCaptureIntent())
        }
    }
}