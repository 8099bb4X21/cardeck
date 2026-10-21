package io.github.shaonialife.cardeck.bridge

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import io.github.shaonialife.cardeck.util.Bg

/**
 * 远程调试设置页（remote 变体的第二个桌面入口）：
 * 填服务器地址与配对码 → 连接；展示连接状态；引导悬浮窗/安装权限。
 */
class BridgeSetupActivity : AppCompatActivity() {
    private val refresh = Handler(Looper.getMainLooper())
    private val ticker =
        object : Runnable {
            override fun run() {
                statusText?.text = "状态：${BridgeClient.stateText}"
                refresh.postDelayed(this, 1_000)
            }
        }
    private var statusText: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "CarDeck 远程调试"

        val pad = (16 * resources.displayMetrics.density).toInt()
        val root =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(pad, pad, pad, pad)
            }
        val scroll = ScrollView(this).apply { addView(root) }
        setContentView(scroll)

        fun label(text: String): TextView =
            TextView(this).apply {
                this.text = text
                setTextColor(0xFFE0E0E5.toInt())
                textSize = 13f
            }

        val urlInput =
            EditText(this).apply {
                hint = "wss://example.trycloudflare.com  或  ws://192.168.1.2:8787"
                setText(BridgePrefs.serverUrl(this@BridgeSetupActivity))
                imeOptions = EditorInfo.IME_ACTION_NEXT
                setSingleLine()
            }
        val codeInput =
            EditText(this).apply {
                hint = "6 位配对码（服务器控制台显示）"
                setText(BridgePrefs.pairCode(this@BridgeSetupActivity))
                setSingleLine()
            }
        val connect =
            Button(this).apply {
                text = "连接"
                setOnClickListener {
                    BridgePrefs.save(
                        this@BridgeSetupActivity,
                        urlInput.text.toString(),
                        codeInput.text.toString(),
                    )
                    BridgePrefs.setAutoConnect(this@BridgeSetupActivity, true)
                    // 悬浮窗权限顺手引导（提示窗依赖）
                    if (!Settings.canDrawOverlays(this@BridgeSetupActivity)) {
                        runCatching {
                            startActivity(
                                Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    android.net.Uri.parse("package:$packageName"),
                                ),
                            )
                        }
                    }
                    Bg.run("bridge-start") { BridgeClient.start(this@BridgeSetupActivity) }
                }
            }
        val disconnect =
            Button(this).apply {
                text = "断开"
                setOnClickListener {
                    BridgePrefs.setAutoConnect(this@BridgeSetupActivity, false)
                    BridgeClient.stop()
                }
            }
        val installPerm =
            Button(this).apply {
                text = "授予「安装未知应用」权限"
                visibility =
                    if (packageManager.canRequestPackageInstalls()) View.GONE else View.VISIBLE
                setOnClickListener {
                    runCatching {
                        startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                android.net.Uri.parse("package:$packageName"),
                            ),
                        )
                    }
                }
            }
        statusText =
            TextView(this).apply {
                text = "状态：${BridgeClient.stateText}"
                setTextColor(0xFF3DD6C4.toInt())
                textSize = 14f
            }

        val note =
            TextView(this).apply {
                text =
                    "说明：\n" +
                    "· 本应用与日常版（同名无「远程调试」后缀）互相独立，需各自授权一次" +
                    "Shizuku 与通知使用权\n" +
                    "· 连接后本机会向调试服务器持续推送运行日志（含热点名/蓝牙地址等），" +
                    "仅用于故障排查\n" +
                    "· 调试服务器仅能执行白名单命令（发起/断开连接、查询状态）"
                setTextColor(0xFFE0E0E5.toInt())
                textSize = 13f
                setLineSpacing(0f, 1.3f)
            }

        listOf(
            label("调试服务器地址"),
            urlInput,
            label("配对码"),
            codeInput,
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.START
                addView(connect)
                addView(disconnect)
            },
            installPerm,
            statusText!!,
            label(""),
            note,
        ).forEach { view -> root.addView(view) }
    }

    override fun onResume() {
        super.onResume()
        refresh.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        refresh.removeCallbacks(ticker)
    }
}
