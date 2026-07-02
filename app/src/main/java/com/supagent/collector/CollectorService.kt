package com.supagent.collector

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.usage.UsageStatsManager
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.google.gson.Gson
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.TimeUnit

/**
 * 核心采集服务：无 UI，每 60 秒读取 UsageStatsManager 并上报。
 *
 * 数据流：
 * UsageStatsManager → JSON → HTTP POST → SupAgent 服务器 → 娱乐检测 → 微信提醒
 */
class CollectorService : Service() {

    companion object {
        private const val TAG = "SupAgent"
        private const val CHANNEL_ID = "supagent_collector"
        private const val NOTIFICATION_ID = 1
        private const val POLL_INTERVAL_MS = 60_000L  // 60 秒
    }

    private val timer = Timer()
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()
    private val gson = Gson()

    private var serverUrl = ""
    private var authToken = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("准备就绪"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        serverUrl = intent?.getStringExtra("server_url") ?: ""
        authToken = intent?.getStringExtra("auth_token") ?: ""

        if (serverUrl.isEmpty()) {
            stopSelf()
            return START_NOT_STICKY
        }

        // 启动定时采集
        timer.scheduleAtFixedRate(object : TimerTask() {
            override fun run() {
                collectAndUpload()
            }
        }, 0, POLL_INTERVAL_MS)

        updateNotification("运行中 - 每 60 秒采集")
        Log.i(TAG, "采集服务启动，服务器: $serverUrl")
        return START_STICKY
    }

    override fun onDestroy() {
        timer.cancel()
        super.onDestroy()
        Log.i(TAG, "采集服务停止")
    }

    /**
     * 核心：采集 UsageStatsManager 数据并上传。
     */
    private fun collectAndUpload() {
        try {
            val usm = getSystemService(USAGE_STATS_SERVICE) as UsageStatsManager
            val now = System.currentTimeMillis()
            val oneMinuteAgo = now - POLL_INTERVAL_MS

            // 查询最近 1 分钟的 App 使用情况
            val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, oneMinuteAgo, now)

            if (stats.isNullOrEmpty()) {
                Log.w(TAG, "无使用数据（可能未授权）")
                return
            }

            // 找到当前前台 App（lastTimeUsed 最大的）
            val foreground = stats.maxByOrNull { it.lastTimeUsed }

            if (foreground != null) {
                val packageName = foreground.packageName
                val appName = getAppName(packageName)

                // 分类
                val category = classifyApp(packageName)

                // 上报
                val report = mapOf(
                    "timestamp" to now,
                    "package_name" to packageName,
                    "app_name" to appName,
                    "usage_seconds" to (POLL_INTERVAL_MS / 1000).toInt(),
                    "category" to category
                )

                postJson("/api/phone/usage", report)
            }
        } catch (e: Exception) {
            Log.e(TAG, "采集失败: ${e.message}")
        }
    }

    /**
     * App 分类：娱乐 / 学习 / 工作 / 社交 / 其他
     */
    private fun classifyApp(packageName: String): String {
        val entertainment = listOf(
            "com.ss.android.ugc.aweme",     // 抖音
            "com.smile.gifmaker",            // 快手
            "com.bilibili.app.in",           // B站
            "tv.danmaku.bili",               // B站
            "com.tencent.tmgp.sgame",        // 王者荣耀
            "com.tencent.tmgp.pubgmhd",     // 和平精英
            "com.youku.phone",               // 优酷
            "com.qiyi.video",                // 爱奇艺
            "com.tencent.qqlive",            // 腾讯视频
            "com.netease.cloudmusic",        // 网易云音乐
        )
        val social = listOf(
            "com.tencent.mm",                // 微信
            "com.tencent.mobileqq",          // QQ
            "com.sina.weibo",                // 微博
        )
        val learning = listOf(
            "com.langbridge",                // 多邻国
            "com.duolingo",                  // Duolingo
            "cn.duokao.app",                 // 背单词
        )

        return when {
            entertainment.contains(packageName) -> "entertainment"
            social.contains(packageName) -> "social"
            learning.contains(packageName) -> "productivity"
            else -> "other"
        }
    }

    /**
     * 获取 App 显示名称。
     */
    private fun getAppName(packageName: String): String {
        return try {
            val pm = packageManager
            val info = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(info).toString()
        } catch (e: Exception) {
            packageName
        }
    }

    /**
     * HTTP POST JSON 到服务器。
     */
    private fun postJson(path: String, data: Any) {
        val json = gson.toJson(data)
        val body = json.toRequestBody("application/json".toMediaType())

        val requestBuilder = Request.Builder()
            .url("$serverUrl$path")
            .post(body)

        if (authToken.isNotEmpty()) {
            requestBuilder.addHeader("Authorization", "Bearer $authToken")
        }

        try {
            val response = client.newCall(requestBuilder.build()).execute()
            if (response.isSuccessful) {
                Log.d(TAG, "上报成功: $path")
            } else {
                Log.w(TAG, "上报失败: HTTP ${response.code}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "上报异常: ${e.message}")
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "SupAgent 采集",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "后台采集手机使用数据"
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("SupAgent")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }
}
