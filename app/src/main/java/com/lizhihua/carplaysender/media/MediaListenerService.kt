package com.lizhihua.carplaysender.media

import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.media.MediaMetadata
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * 通知监听服务：借通知使用权枚举系统活跃媒体会话，向应用提供「正在播放」状态
 * （歌名/歌手/播放中）。音乐卡片（仪表盘）据此渲染与控制。
 *
 * 授权路径：系统设置「通知使用权」，或 Shizuku（shell）执行
 * `cmd notification allow_listener <本组件>`（MainActivity 启动时自动尝试）。
 */
class MediaListenerService : NotificationListenerService() {

    companion object {
        fun componentName(context: android.content.Context): ComponentName =
            ComponentName(context, MediaListenerService::class.java)
    }

    private val manager by lazy {
        getSystemService(android.content.Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
    }

    private val sessionListener =
        MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
            update(controllers ?: emptyList())
        }

    /** 已注册播放回调的会话（避免重复注册）。 */
    private val watched = HashSet<android.media.session.MediaSession.Token>()

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /** 兜底轮询：部分播放器不触发回调/回调丢失时保证卡片信息更新。 */
    private val periodicRefresh = object : Runnable {
        override fun run() {
            refresh()
            mainHandler.postDelayed(this, 3_000)
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        try {
            manager.addOnActiveSessionsChangedListener(
                sessionListener, componentName(this),
            )
            MediaSessionsState.resyncHook = { mainHandler.post { refresh() } }
            update(manager.getActiveSessions(componentName(this)))
            mainHandler.post(periodicRefresh)
        } catch (_: SecurityException) {
            // 通知使用权被撤销：静默（授权后系统会重新绑定本服务）
        }
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(periodicRefresh)
        runCatching { manager.removeOnActiveSessionsChangedListener(sessionListener) }
        super.onDestroy()
    }

    private fun update(controllers: List<android.media.session.MediaController>) {
        controllers.forEach { controller ->
            if (watched.add(controller.sessionToken)) {
                controller.registerCallback(
                    object : android.media.session.MediaController.Callback() {
                    override fun onPlaybackStateChanged(state: PlaybackState?) {
                        refresh()
                    }

                    override fun onMetadataChanged(metadata: MediaMetadata?) {
                        refresh()
                    }
                },
                android.os.Handler(android.os.Looper.getMainLooper()),
            )
        }
        }
        refresh()
    }

    /** 从活跃会话提取正在播放态：优先 PLAYING 的会话，否则保留第一个有元数据的。 */
    private fun refresh() {
        val controllers = try {
            manager.getActiveSessions(componentName(this))
        } catch (_: SecurityException) {
            emptyList()
        }
        val picked = controllers.firstOrNull {
            it.playbackState?.state == PlaybackState.STATE_PLAYING
        } ?: controllers.firstOrNull { it.metadata != null }
        val now = picked?.let { controller ->
            val meta = controller.metadata
            val playing = controller.playbackState?.state == PlaybackState.STATE_PLAYING
            MediaSessionsState.NowPlaying(
                title = meta?.getString(MediaMetadata.METADATA_KEY_TITLE),
                artist = meta?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                    ?: meta?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST),
                playing = playing,
            )
        }
        MediaSessionsState.publish(now)
    }
}

/** 全局「正在播放」状态（本进程内共享；GridActivity 音乐卡监听渲染）。 */
object MediaSessionsState {

    data class NowPlaying(
        val title: String?,
        val artist: String?,
        val playing: Boolean,
    )

    @Volatile
    var nowPlaying: NowPlaying? = null
        private set

    private val listeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    fun publish(value: NowPlaying?) {
        if (nowPlaying == value) return
        nowPlaying = value
        com.lizhihua.carplaysender.launcher.VirtualLauncher.logSink?.invoke(
            "MEDIA: " + (value?.let { "${it.title ?: "?"} - ${it.artist ?: "?"} playing=${it.playing}" }
                ?: "无活跃会话"),
        )
        listeners.forEach { runCatching { it() } }
    }

    /** 重同步钩子（服务连接时安装）：UI 乐观更新后调用以拉取真实状态。 */
    @Volatile
    var resyncHook: (() -> Unit)? = null

    fun requestResync() {
        resyncHook?.invoke()
    }
}
