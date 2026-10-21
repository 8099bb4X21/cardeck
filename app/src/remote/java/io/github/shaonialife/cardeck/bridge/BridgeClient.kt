package io.github.shaonialife.cardeck.bridge

import android.content.Context
import android.os.Handler
import android.os.Looper
import io.github.shaonialife.cardeck.BuildConfig
import io.github.shaonialife.cardeck.util.Bg
import io.github.shaonialife.cardeck.util.DebugFileLog
import org.json.JSONArray
import org.json.JSONObject
import java.util.ArrayDeque

/**
 * 远程调试桥客户端（remote 变体专属）。
 *
 * 职责：与调试服务器保持一条 WSS/WS 长连接——配对、日志（批量回补 + 实时流）、
 * 命令白名单、提示下发、新版本推送。会话与业务代码的耦合点只有两处：
 * [DebugFileLog.tap]（读日志）与 [BridgeCommands]（执行命令，走既有公开 API）。
 *
 * 线程：自持一根「bridge-client」连接线程（长驻循环线程不进 Bg 池的项目约定）；
 * WS 收到的消息在连接线程解析后按需切主线程（悬浮窗）或 Bg（命令）。
 */
object BridgeClient {
    /** 连接状态（设置页展示；主线程读）。 */
    @Volatile
    var stateText: String = "未连接"
        private set

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var running = false

    @Volatile
    private var ws: WsClient? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 环形日志缓冲（断线重连后按 seq 回补给服务端）。 */
    private val ringLock = Any()
    private val ring = ArrayDeque<Pair<Long, String>>()
    private const val RING_CAP = 5_000

    /** 待实时发送的日志（tap 线程入队，连接线程按批冲刷）。 */
    private val pendingLock = Any()
    private val pending = ArrayDeque<Pair<Long, String>>()

    private var tapInstalled = false

    fun start(context: Context) {
        appContext = context.applicationContext
        installTap()
        if (running) return
        running = true
        Thread({ runLoop() }, "bridge-client").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running = false
        ws?.close(1000, "客户端停止")
        setState("已断开")
    }

    private fun setState(text: String) {
        stateText = text
        DebugFileLog.tap?.let {} // no-op：保持对象引用稳定（状态不进文件日志，避免回声）
    }

    private fun installTap() {
        if (tapInstalled) return
        tapInstalled = true
        DebugFileLog.tap = { seq, line ->
            synchronized(ringLock) {
                ring.addLast(seq to line)
                while (ring.size > RING_CAP) ring.removeFirst()
            }
            synchronized(pendingLock) { pending.addLast(seq to line) }
        }
    }

    private fun runLoop() {
        var backoffMs = 2_000L
        while (running) {
            val ctx = appContext ?: return
            val url = BridgePrefs.serverUrl(ctx)
            if (url.isBlank()) {
                setState("未配置服务器地址")
                sleepQuiet(3_000)
                continue
            }
            val client =
                WsClient(
                    url,
                    object : WsClient.Listener {
                        override fun onOpen() {
                            setState("已连接，配对中…")
                        }

                        override fun onText(text: String) = onServerMessage(text)

                        override fun onClosed(reason: String) {
                            setState("连接断开：$reason")
                        }
                    },
                )
            ws = client
            setState("连接中…")
            try {
                client.connect()
                backoffMs = 2_000L
            } catch (_: InterruptedException) {
                return
            } catch (_: Exception) {
                // 连接失败/断开：退避重连
            }
            ws = null
            if (!running) break
            sleepQuiet(backoffMs)
            backoffMs = (backoffMs * 2).coerceAtMost(30_000L)
        }
    }

    private fun sleepQuiet(millis: Long) {
        try {
            Thread.sleep(millis)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    // ---- 连接建立后的握手与消息 ----

    private fun onServerMessage(text: String) {
        val msg =
            runCatching { JSONObject(text) }.getOrNull() ?: return
        when (msg.optString("t")) {
            "welcome" -> {
                if (msg.optBoolean("ok")) {
                    setState("已连接（${msg.optLong("pairedAt")}）")
                    sendHello()
                    // 全量回补：服务端按 seq 去重并留环形缓冲
                    sendLogBatch(since = 0)
                } else {
                    setState("配对被拒：${msg.optString("error")}")
                    ws?.close(1008, "配对被拒")
                }
            }

            "log.sync" -> sendLogBatch(msg.optLong("since"))

            "cmd" -> {
                val id = msg.optString("id")
                val name = msg.optString("name")
                val args = msg.optJSONObject("args") ?: JSONObject()
                Bg.run("bridge-cmd") {
                    val result = BridgeCommands.handle(appContext, name, args)
                    send(
                        JSONObject()
                            .put("t", "cmd.result")
                            .put("id", id)
                            .put("ok", result.optBoolean("ok"))
                            .put("detail", result.optString("detail")),
                    )
                }
            }

            "notify" -> {
                val message = msg.optString("text")
                mainHandler.post { BridgeOverlay.show(appContext, message, dismissMillis = 8_000) }
            }

            "apk.offer" ->
                ReinstallFlow.offer(
                    appContext,
                    msg.optString("url"),
                    msg.optString("sha256"),
                    msg.optString("version"),
                )

            "ping" -> send(JSONObject().put("t", "pong").put("id", msg.optString("id")))
        }
    }

    private fun sendHello() {
        val ctx = appContext ?: return
        val hello =
            JSONObject()
                .put("t", "hello")
                .put("deviceId", BridgePrefs.deviceId(ctx))
                .put("deviceModel", android.os.Build.MODEL)
                .put("android", android.os.Build.VERSION.RELEASE)
                .put("appVersion", BuildConfig.VERSION_NAME)
                .put("code", BridgePrefs.pairCode(ctx))
                .put("lastSeq", DebugFileLog.lastSeq)
        send(hello)
    }

    /** 回补环形缓冲中 seq > since 的日志（since=0 全量）。 */
    private fun sendLogBatch(since: Long) {
        val snapshot: List<Pair<Long, String>> =
            synchronized(ringLock) {
                ring.filter { it.first > since }
            }
        if (snapshot.isEmpty()) {
            send(JSONObject().put("t", "log.batch").put("lines", JSONArray()))
            return
        }
        // 分片发送：单条 JSON 控制在 ~256KB 内（服务端 ws 消息上限友好）
        snapshot.chunked(200).forEach { chunk ->
            val lines = JSONArray()
            chunk.forEach { (seq, line) ->
                lines.put(JSONObject().put("seq", seq).put("text", line))
            }
            send(JSONObject().put("t", "log.batch").put("lines", lines))
        }
    }

    /** 冲刷实时日志队列（连接线程周期调用）。 */
    fun flushPendingLogs() {
        val batch =
            synchronized(pendingLock) {
                if (pending.isEmpty()) return
                val taken = ArrayList<Pair<Long, String>>(pending.size)
                while (pending.isNotEmpty()) taken.add(pending.removeFirst())
                taken
            }
        val lines = JSONArray()
        batch.forEach { (seq, line) -> lines.put(JSONObject().put("seq", seq).put("text", line)) }
        send(JSONObject().put("t", "log.batch").put("lines", lines))
    }

    fun send(json: JSONObject) {
        ws?.let { client ->
            if (!client.sendText(json.toString())) {
                synchronized(pendingLock) {} // 发送失败由重连补发（环形缓冲回补路径）
            }
        }
    }

    /** 供设置页手动触发一次状态广播。 */
    fun requestStatus() {
        send(JSONObject().put("t", "status").put("state", stateText))
    }

    init {
        // 实时日志冲刷节拍：独立 daemon 线程，500ms 一批
        Thread(
            {
                while (true) {
                    sleepQuiet(500)
                    if (running) flushPendingLogs()
                }
            },
            "bridge-log-flush",
        ).apply {
            isDaemon = true
            start()
        }
    }
}
