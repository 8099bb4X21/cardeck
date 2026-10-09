package com.lizhihua.carplaysender.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * 前台保活服务：连接建立期间以 connectedDevice 类型常驻，
 * 防止 app 退到后台后被系统/MagicOS 省电策略杀死导致断连。
 * 连接逻辑仍运行在 MainActivity 派生的线程里——本服务负责保住进程。
 */
class CarPlayService : Service() {

    private var wakeLock: android.os.PowerManager.WakeLock? = null
    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * 息屏保活：CPU 唤醒锁维持编码/协议线程与 TCP 存活探测的响应；
     * WiFi 低延迟锁阻止无线进入激进省电（否则接收端 20s 内判死拆链）。
     */
    private fun acquireLocks() {
        if (wakeLock?.isHeld == true && wifiLock?.isHeld == true) return
        val power = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        wakeLock = power.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "CarPlaySender:session").apply {
            setReferenceCounted(false)
            acquire()
        }
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
        wifiLock = wifi.createWifiLock(
            android.net.wifi.WifiManager.WIFI_MODE_FULL_LOW_LATENCY,
            "CarPlaySender:wifi",
        ).apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseLocks() {
        runCatching { wakeLock?.release() }
        wakeLock = null
        runCatching { wifiLock?.release() }
        wifiLock = null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        acquireLocks()
        // Android 14+ 顺序要求：先以 mediaProjection 类型声明前台，再 getMediaProjection；
        // 否则主线程 SecurityException 闪退
        if (pendingProjectionResult != null) {
            upgradeToProjection()
            takeProjection()
        } else if (mediaProjection != null) {
            upgradeToProjection()
        }
        return START_STICKY
    }

    private fun takeProjection() {
        val (code, data) = pendingProjectionResult ?: return
        pendingProjectionResult = null
        try {
            val manager =
                getSystemService(Context.MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
            val projection = manager.getMediaProjection(code, data)
            // Android 14+：createVirtualDisplay 前必须注册回调（资源管理强制）
            projection?.registerCallback(
                object : android.media.projection.MediaProjection.Callback() {
                    override fun onStop() {
                        mediaProjection = null
                        android.util.Log.i("CarPlayService", "MediaProjection 停止")
                    }
                },
                android.os.Handler(android.os.Looper.getMainLooper()),
            )
            mediaProjection = projection
            android.util.Log.i("CarPlayService", "MediaProjection 就绪")
        } catch (error: Exception) {
            android.util.Log.w("CarPlayService", "getMediaProjection 失败：${error.message}")
        }
    }

    private fun upgradeToProjection() {
        try {
            androidx.core.app.ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            )
        } catch (error: Exception) {
            android.util.Log.w("CarPlayService", "升级 mediaProjection FGS 类型失败：${error.message}")
        }
    }

    override fun onDestroy() {
        releaseLocks()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "carplay_connection"
        private const val NOTIFICATION_ID = 1

        /** 镜像模式：授权后由 MainActivity 写入；服务重声明 FGS 类型后方可创建虚拟屏。 */
        @Volatile
        var mediaProjection: android.media.projection.MediaProjection? = null

        /** 授权结果暂存：由服务在前台类型升级后调用 getMediaProjection（Android 14+ 顺序要求）。 */
        @Volatile
        var pendingProjectionResult: Pair<Int, Intent>? = null

        fun start(context: Context) {
            context.startForegroundService(Intent(context, CarPlayService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CarPlayService::class.java))
        }
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "CarPlay 连接",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "保持与车机的 CarPlay 连接"
                    setShowBadge(false)
                },
            )
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("CarPlay Sender 运行中")
            .setContentText("正在保持与车机的连接，点开应用可查看日志")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
