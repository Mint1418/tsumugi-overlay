package com.operit.overflow

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var startButton: Button

    private val requestOverlayPermission =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            checkAllPermissions()
        }

    private val requestUsageStats =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            checkAllPermissions()
        }

    private val requestNotificationPermission = if (Build.VERSION.SDK_INT >= 33)
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            checkAllPermissions()
        } else null

    private val requestStoragePermission = if (Build.VERSION.SDK_INT >= 33)
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            checkAllPermissions()
        } else registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            checkAllPermissions()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        startButton = findViewById(R.id.startButton)

        startButton.setOnClickListener {
            if (checkAllPermissions()) {
                startOverlayService()
            } else {
                requestMissingPermissions()
            }
        }

        // Check if service is already running
        if (OverlayService.isRunning) {
            statusText.text = "🟢 紬正在陪你"
            startButton.text = "停止"
            startButton.setOnClickListener {
                stopService(Intent(this, OverlayService::class.java))
                statusText.text = "⚪ 紬休息了"
                startButton.text = "开始"
                startButton.setOnClickListener {
                    startOverlayService()
                }
            }
        }

        checkAllPermissions()
    }

    private fun checkAllPermissions(): Boolean {
        val missing = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) missing.add("悬浮窗权限")
        }

        if (!isUsageStatsEnabled()) missing.add("使用情况访问权限")

        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) missing.add("通知权限")
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES)
                != PackageManager.PERMISSION_GRANTED
            ) missing.add("媒体读取权限")
        }

        updateStatus(missing)
        return missing.isEmpty()
    }

    private fun updateStatus(missing: List<String>) {
        if (missing.isEmpty()) {
            statusText.text = "✅ 所有权限就绪，可以开启紬了"
            startButton.isEnabled = true
        } else {
            statusText.text = "⚠️ 缺少权限：${missing.joinToString("、")}"
            startButton.isEnabled = true
        }
    }

    private fun requestMissingPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                AlertDialog.Builder(this)
                    .setTitle("需要悬浮窗权限")
                    .setMessage("紬需要悬浮在其他应用上才能陪在你身边")
                    .setPositiveButton("去设置") { _, _ ->
                        val intent = Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:$packageName")
                        )
                        requestOverlayPermission.launch(intent)
                    }
                    .setNegativeButton("取消", null)
                    .show()
                return
            }
        }

        if (!isUsageStatsEnabled()) {
            AlertDialog.Builder(this)
                .setTitle("需要使用情况访问权限")
                .setMessage("紬需要知道你在用什么应用才能做出反应")
                .setPositiveButton("去设置") { _, _ ->
                    startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                }
                .setNegativeButton("取消", null)
                .show()
            return
        }

        if (Build.VERSION.SDK_INT >= 33) {
            requestNotificationPermission?.launch(Manifest.permission.POST_NOTIFICATIONS)
            requestStoragePermission.launch(
                arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
            )
        }
    }

    private fun isUsageStatsEnabled(): Boolean {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
        val mode = appOps.unsafeCheckOpNoThrow(
            android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(), packageName
        )
        return mode == android.app.AppOpsManager.MODE_ALLOWED
    }

    private fun startOverlayService() {
        if (checkAllPermissions()) {
            val intent = Intent(this, OverlayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            statusText.text = "🟢 紬正在陪你"
            startButton.text = "停止"
            startButton.setOnClickListener {
                stopService(Intent(this, OverlayService::class.java))
                statusText.text = "⚪ 紬休息了"
                startButton.text = "开始"
                startButton.setOnClickListener {
                    startOverlayService()
                }
            }
            Toast.makeText(this, "紬来找你了", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "请先授予所有权限", Toast.LENGTH_SHORT).show()
        }
    }
}
