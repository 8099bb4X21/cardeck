package com.lizhihua.carplaysender

import android.app.Application
import android.util.Log

/**
 * 进程级安全网：任一线程的未捕获异常只记录并让该线程结束，
 * 不再走默认处理器杀死整个进程（连接会话横跨多个后台线程，
 * 一个套接字异常不应毁掉全部链路与前台服务）。
 * 各线程自身仍应尽量用 try/catch 精确处理；这里是最后防线。
 */
class SenderApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e(TAG, "未捕获异常（线程 ${thread.name}，已吞掉保进程）", throwable)
            // 不调用 previous：默认处理器会 killProcess 杀死整个 app
        }
    }

    companion object {
        private const val TAG = "CarPlaySender"
    }
}
