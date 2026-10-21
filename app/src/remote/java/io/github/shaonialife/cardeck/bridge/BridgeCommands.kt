package io.github.shaonialife.cardeck.bridge

import android.content.Context
import io.github.shaonialife.cardeck.SessionController
import org.json.JSONObject

/**
 * 调试命令白名单（**绝不放开任意 shell/反射**——远程通道被劫持时的爆炸半径
 * 限定在这几个动作）。全部走既有公开 API，遵守「UI 线程禁 shell」约定。
 */
object BridgeCommands {
    fun handle(
        context: Context?,
        name: String,
        args: JSONObject,
    ): JSONObject {
        val ctx =
            context?.applicationContext
                ?: return fail("应用上下文未就绪")
        return when (name) {
            "ping" -> JSONObject().put("ok", true).put("detail", "pong ${System.currentTimeMillis()}")

            "get_state" ->
                JSONObject()
                    .put("ok", true)
                    .put("detail", SessionController.statusLine() + " 会话=${if (SessionController.isSessionRunning()) "运行中" else "停止"}")

            "start_connection" -> {
                // 与手机上点「启动连接」等价：前置（Shizuku/蓝牙）不满足时日志会说明
                SessionController.ensure(ctx)
                SessionController.startConnection(ctx)
                JSONObject().put("ok", true).put("detail", "已发起连接（进度见日志）")
            }

            "stop_connection" -> {
                SessionController.disconnect(ctx)
                JSONObject().put("ok", true).put("detail", "已断开")
            }

            else -> fail("未知命令：$name")
        }
    }

    private fun fail(detail: String): JSONObject = JSONObject().put("ok", false).put("detail", detail)
}
