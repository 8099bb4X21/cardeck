package com.lizhihua.carplaysender.ui

import android.app.Presentation
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.widget.Button
import android.widget.TextView
import com.lizhihua.carplaysender.R
import com.lizhihua.carplaysender.input.TouchReports
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 车机桌面渲染器：VirtualDisplay + Presentation。
 *
 * 把编码器的输入 Surface 包装成虚拟显示器，在其上挂载标准 View 层级
 * （布局/滚动/手势全部由系统处理，硬件加速渲染）。车机触摸转成
 * MotionEvent 直接投给视图树 —— 点击、滚动、惯性照常工作。
 * UI 开发从此就是普通 Android 开发（XML/Compose 均可）。
 */
class CarDisplayRenderer(
    private val context: Context,
    private val width: Int,
    private val height: Int,
    private val log: (String) -> Unit,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var virtualDisplay: android.hardware.display.VirtualDisplay? = null
    private var presentation: Presentation? = null
    private var attachedSurface: Surface? = null

    // 触摸序列状态（单指；View 体系按 down/move/up 序列消费）
    private var touchDownTime = 0L
    private var touchPressed = false

    /** 把虚拟显示器挂到编码 Surface 上（在主线程执行）。 */
    fun attach(surface: Surface) {
        mainHandler.post {
            try {
                val manager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
                val display = manager.createVirtualDisplay(
                    "CarPlaySender",
                    width,
                    height,
                    420,
                    surface,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
                    null,
                    null,
                )
                if (display == null) {
                    log("CAR-UI: 创建虚拟显示器失败")
                    return@post
                }
                virtualDisplay = display
                attachedSurface = surface
                var lastStatus = ""
                val content = object : Presentation(
                    context,
                    display.display,
                    android.R.style.Theme_DeviceDefault_NoActionBar_Fullscreen,
                ) {
                    override fun onCreate(savedInstanceState: Bundle?) {
                        super.onCreate(savedInstanceState)
                        setContentView(R.layout.car_home)
                        val status = findViewById<TextView>(R.id.statusView)
                        val logView = findViewById<TextView>(R.id.logView)
                        var taps = 0
                        val onClick = { label: String ->
                            taps++
                            lastStatus = "$label 被点击，共 $taps 次"
                            logView.append("$label 点击 #$taps\n")
                        }
                        findViewById<Button>(R.id.btnRed).setOnClickListener { onClick("红色") }
                        findViewById<Button>(R.id.btnGreen).setOnClickListener { onClick("绿色") }
                        findViewById<Button>(R.id.btnBlue).setOnClickListener { onClick("蓝色") }
                        // 时钟每 33ms 刷新：驱动持续绘制，保证视频流不因静止而中断
                        val format = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
                        val tick = object : Runnable {
                            override fun run() {
                                if (presentation == null || !status.isAttachedToWindow) return
                                status.text = format.format(Date()) + "  " + lastStatus
                                mainHandler.postDelayed(this, 33)
                            }
                        }
                        mainHandler.post(tick)
                        log("CAR-UI: ✓ Presentation 已上屏（虚拟显示器 ${width}x$height@420dpi）")
                    }
                }
                content.show()
                presentation = content
            } catch (error: Exception) {
                log("CAR-UI: 渲染器启动失败 - ${error.message}")
            }
        }
    }

    /** 车机触摸 → MotionEvent → 视图树（点击/滚动/惯性由此自然工作）。 */
    fun dispatchTouch(contact: TouchReports.Contact) {
        if (!contact.down && contact.xPixels == 0 && contact.yPixels == 0) return
        mainHandler.post {
            val decorView: View = presentation?.window?.decorView ?: return@post
            val now = SystemClock.uptimeMillis()
            if (contact.down) {
                touchDownTime = if (!touchPressed) now else touchDownTime
                touchPressed = true
            }
            val action = when {
                contact.down && now == touchDownTime -> MotionEvent.ACTION_DOWN
                contact.down -> MotionEvent.ACTION_MOVE
                else -> {
                    touchPressed = false
                    MotionEvent.ACTION_UP
                }
            }
            val event = MotionEvent.obtain(
                touchDownTime,
                now,
                action,
                contact.xPixels.toFloat(),
                contact.yPixels.toFloat(),
                0,
            )
            decorView.dispatchTouchEvent(event)
            event.recycle()
        }
    }

    fun release() {
        mainHandler.post {
            presentation?.dismiss()
            presentation = null
            virtualDisplay?.release()
            virtualDisplay = null
            attachedSurface = null
            log("CAR-UI: 渲染器已释放")
        }
    }
}
