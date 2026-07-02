package com.supagent.collector

import android.app.Activity
import android.app.AppOpsManager
import android.content.Intent
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var serverInput: EditText
    private lateinit var tokenInput: EditText
    private lateinit var statusText: TextView
    private lateinit var toggleBtn: Button

    private var isRunning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }

        // 标题
        layout.addView(TextView(this).apply {
            text = "📊 SupAgent Collector"
            textSize = 24f
            setPadding(0, 0, 0, 32)
        })

        // 服务器地址
        layout.addView(TextView(this).apply {
            text = "服务器地址 (如 http://192.168.1.100:8080)"
            textSize = 14f
        })
        serverInput = EditText(this).apply {
            hint = "http://192.168.1.100:8080"
            setText("http://10.0.2.2:8080")  // Android 模拟器默认
        }
        layout.addView(serverInput)

        // Token
        layout.addView(TextView(this).apply {
            text = "Token (可选)"
            textSize = 14f
            setPadding(0, 16, 0, 0)
        })
        tokenInput = EditText(this).apply {
            hint = "your-secret-token"
        }
        layout.addView(tokenInput)

        // 状态
        statusText = TextView(this).apply {
            text = "状态: 未运行"
            textSize = 16f
            setPadding(0, 24, 0, 8)
        }
        layout.addView(statusText)

        // 启动/停止按钮
        toggleBtn = Button(this).apply {
            text = "▶ 启动采集"
            setOnClickListener { toggle() }
        }
        layout.addView(toggleBtn)

        // 提示
        layout.addView(TextView(this).apply {
            text = "\n使用说明:\n1. 先在电脑上启动 SupAgent\n2. 填入电脑的 IP 地址\n3. 点击启动\n4. 授权「使用情况访问权限」\n5. 保持 App 在后台运行"
            textSize = 12f
            setPadding(0, 24, 0, 0)
        })

        setContentView(layout)

        // 恢复上次的服务器地址
        val prefs = getSharedPreferences("supagent", MODE_PRIVATE)
        serverInput.setText(prefs.getString("server", "http://10.0.2.2:8080"))
        tokenInput.setText(prefs.getString("token", ""))
    }

    private fun toggle() {
        if (isRunning) {
            stopService(Intent(this, CollectorService::class.java))
            isRunning = false
            toggleBtn.text = "▶ 启动采集"
            statusText.text = "状态: 已停止"
        } else {
            if (!hasUsagePermission()) {
                Toast.makeText(this, "请先授权「使用情况访问权限」", Toast.LENGTH_LONG).show()
                startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                return
            }

            val server = serverInput.text.toString().trim()
            if (server.isEmpty()) {
                Toast.makeText(this, "请输入服务器地址", Toast.LENGTH_SHORT).show()
                return
            }

            // 保存配置
            getSharedPreferences("supagent", MODE_PRIVATE).edit().apply {
                putString("server", server)
                putString("token", tokenInput.text.toString().trim())
                apply()
            }

            val intent = Intent(this, CollectorService::class.java).apply {
                putExtra("server_url", server)
                putExtra("auth_token", tokenInput.text.toString().trim())
            }
            startForegroundService(intent)

            isRunning = true
            toggleBtn.text = "⏹ 停止采集"
            statusText.text = "状态: 运行中 ✅\n每 60 秒上报一次"
        }
    }

    private fun hasUsagePermission(): Boolean {
        val appOps = getSystemService(APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isRunning) {
            stopService(Intent(this, CollectorService::class.java))
        }
    }
}
