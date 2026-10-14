package com.lizhihua.carplaysender.render

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 双虚拟屏 GL 合成器：dock 屏 + 应用屏（原生 / 超采样两块，按活动切换）→ 拼接输出到编码器输入 Surface。
 *
 * 三路 SurfaceTexture 作为各虚拟屏的输出；GL 线程轮询新帧标志，有新帧即重绘合成画面。
 * 应用区同一时刻只画两块应用屏中的**活动**一块（[requestActiveApp] 切换；超采样屏
 * 等其画出首帧再切换，避免闪黑）。两块应用屏的内容持续消费（纹理保持最新）。
 * 输入 Surface 在 EGL/纹理就绪后经 [onSurfacesReady] 交给调用方去 createVirtualDisplay。
 */
class DualDisplayComposer(
    private val output: Surface,
    private val outWidth: Int,
    private val outHeight: Int,
    /** true：dock 是底部横条（竖屏车机）；false：dock 是左侧竖条（横屏车机）。 */
    private val dockAtBottom: Boolean = false,
) {

    /** 仪表盘↔桌面滑动进度（0=tile 原位，1=完全滑出左侧），由 GridActivity 随滑动驱动。 */
    @Volatile
    var tileSlideFraction: Float = 0f

    companion object {
        private const val TAG = "DualComposer"
        private const val EGL_RECORDABLE_ANDROID = 0x3142

        /** 仪表盘卡片边距（流像素；与 GridActivity CARD_MARGIN_PX 保持一致）。 */
        private const val TILE_MARGIN = 16f
        private const val TILE_CORNER_RADIUS = 24f
    }

    private val running = AtomicBoolean(false)
    private var glThread: Thread? = null

    /** start() 的就绪闩：GL 交付输入 Surface 后放行（注意不能等合成循环退出） */
    @Volatile
    private var startLatch: CountDownLatch? = null

    /** 是否创建了超采样应用屏（start 时根据传入尺寸确定）。 */
    @Volatile
    private var hasHiInput = false

    /** 是否创建了地图屏输入（start 时根据传入尺寸确定）。 */
    @Volatile
    private var hasMapInput = false

    /** 当前应用区显示的是超采样屏（true）还是原生屏（false）。触摸路由以它为准。 */
    @Volatile
    private var activeAppHi = false

    /** 待切换的目标屏（true=超采样屏；null=无待切换）；切屏等目标出帧，避免闪黑/闪旧帧。 */
    @Volatile
    private var pendingTargetHi: Boolean? = null

    @Volatile
    private var pendingBaseFrame = 0

    @Volatile
    private var pendingSince = 0L

    /** 超采样输入（GL 线程创建；start() 返回后对其他线程安全可读）。 */
    @Volatile
    private var appHi: Input? = null

    /** 原生应用屏输入（同上；requestActiveApp 读其帧号作切换判据）。 */
    @Volatile
    private var appInput: Input? = null

    /** 地图屏输入（GL 线程创建）。 */
    @Volatile
    private var mapInput: Input? = null

    @Volatile
    var onSurfacesReady: ((dock: Surface, app: Surface, appHi: Surface?, map: Surface?) -> Unit)? = null

    @Throws(IllegalStateException::class)
    fun start(
        dockWidthPx: Int,
        dockHeightPx: Int,
        appWidthPx: Int,
        appHeightPx: Int,
        appHiWidthPx: Int = 0,
        appHiHeightPx: Int = 0,
        mapWidthPx: Int = 0,
        mapHeightPx: Int = 0,
    ) {
        if (running.getAndSet(true)) return
        hasHiInput = appHiWidthPx > 0 && appHiHeightPx > 0
        hasMapInput = mapWidthPx > 0 && mapHeightPx > 0
        val ready = CountDownLatch(1)
        startLatch = ready
        var failure: IllegalStateException? = null
        glThread = Thread({
            try {
                runGl(dockWidthPx, dockHeightPx, appWidthPx, appHeightPx, appHiWidthPx, appHiHeightPx, mapWidthPx, mapHeightPx)
            } catch (error: IllegalStateException) {
                failure = error
            } finally {
                ready.countDown()
            }
        }, "dual-composer").apply {
            isDaemon = true
            start()
        }
        ready.await()
        failure?.let { running.set(false); throw it }
    }

    /**
     * 切换应用区显示：false=原生屏，true=超采样屏。目标屏出帧后生效（期间继续显示原
     * 画面）：等待「目标屏帧号前进」（新应用首帧）；600ms 兜底（目标屏有历史内容时
     * 平滑切换）；2s 超时兜底（目标屏迟迟不出帧也要把画面切过去）。
     * 无超采样屏（未创建）时恒为原生屏。
     */
    fun requestActiveApp(hi: Boolean) {
        val target = if (!hasHiInput) false else hi
        if (activeAppHi == target && pendingTargetHi == null) return
        val targetInput = if (target) appHi else appInput
        pendingBaseFrame = targetInput?.frameCount ?: 0
        pendingSince = android.os.SystemClock.uptimeMillis()
        pendingTargetHi = target
        if (!hasHiInput) {
            // 无超采样屏：无需等待，直接生效
            activeAppHi = false
            pendingTargetHi = null
        }
    }

    /** 应用区当前活动屏（触摸路由/坐标换算以此为准）。 */
    fun isActiveAppHi(): Boolean = activeAppHi

    /**
     * 地图屏（tile）布局状态：HIDDEN=普通桌面；SPLIT=采样画面中央带区映射到
     * 应用区左半（地图中心≈车辆位置，裁剪语义正确）；FULLSCREEN=整个应用区。
     * 地图屏始终以全屏尺寸创建（应用窗口稳定，任务不迁移），分/全屏只是采样几何。
     */
    enum class TileLayout { HIDDEN, SPLIT, FULLSCREEN }

    @Volatile
    private var tileLayout = TileLayout.HIDDEN

    /** 切换地图 tile 布局。 */
    fun setTileLayout(layout: TileLayout) {
        tileLayout = layout
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        glThread?.interrupt()
        glThread = null
    }

    private class Input(val texture: Int, val surfaceTexture: SurfaceTexture, val surface: Surface) {
        @Volatile
        var hasNewFrame = false

        /** 是否已产出过至少一帧（未产出的输入不参与绘制，避免空纹理） */
        @Volatile
        var hasContent = false

        /** 累计产出帧数（活动屏切换的「已出帧」判据）。 */
        @Volatile
        var frameCount = 0
    }

    private fun runGl(
        dockW: Int,
        dockH: Int,
        appW: Int,
        appH: Int,
        hiW: Int,
        hiH: Int,
        mapW: Int,
        mapH: Int,
    ) {
        // ---- EGL ----
        val display: EGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        checkEgl(display != EGL14.EGL_NO_DISPLAY, "eglGetDisplay")
        checkEgl(EGL14.eglInitialize(display, IntArray(2), 0, IntArray(2), 1), "eglInitialize")

        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val num = IntArray(1)
        checkEgl(
            EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, num, 0) && num[0] > 0,
            "eglChooseConfig",
        )
        val context: EGLContext = EGL14.eglCreateContext(
            display,
            configs[0],
            EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE),
            0,
        )
        checkEgl(context != EGL14.EGL_NO_CONTEXT, "eglCreateContext")
        val outputSurface: EGLSurface = EGL14.eglCreateWindowSurface(
            display,
            configs[0],
            output,
            intArrayOf(EGL14.EGL_NONE),
            0,
        )
        checkEgl(outputSurface != EGL14.EGL_NO_SURFACE, "eglCreateWindowSurface")
        checkEgl(
            EGL14.eglMakeCurrent(display, outputSurface, outputSurface, context),
            "eglMakeCurrent",
        )

        // ---- 输入纹理 ----
        fun newInput(width: Int, height: Int): Input {
            val tex = IntArray(1)
            GLES20.glGenTextures(1, tex, 0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex[0])
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            val st = SurfaceTexture(tex[0])
            st.setDefaultBufferSize(width, height)
            val input = Input(tex[0], st, Surface(st))
            st.setOnFrameAvailableListener { input.hasNewFrame = true }
            return input
        }

        val dock = newInput(dockW, dockH)
        val app = newInput(appW, appH).also { appInput = it }
        val hi = if (hasHiInput) newInput(hiW, hiH).also { appHi = it } else null
        val map = if (hasMapInput) newInput(mapW, mapH).also { mapInput = it } else null

        // ---- 着色器 ----
        val program = buildProgram(oes = true)
        GLES20.glUseProgram(program)
        val aPos = GLES20.glGetAttribLocation(program, "aPos")
        val aTexCoord = GLES20.glGetAttribLocation(program, "aTexCoord")
        val uSTMatrix = GLES20.glGetUniformLocation(program, "uSTMatrix")
        val uTexture = GLES20.glGetUniformLocation(program, "uTexture")
        val uClipEnabled = GLES20.glGetUniformLocation(program, "uClipEnabled")
        val uClipRect = GLES20.glGetUniformLocation(program, "uClipRect")
        val uClipRadius = GLES20.glGetUniformLocation(program, "uClipRadius")
        GLES20.glUniform1i(uTexture, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)

        val matrix = FloatArray(16)

        /** [u0]/[u1]：横向纹理采样区间（地图分屏时采样缓冲中央带区用）。 */
        fun drawInputUV(
            input: Input,
            x0: Float,
            x1: Float,
            y0: Float,
            y1: Float,
            u0: Float,
            u1: Float,
            cornerRadius: Float = 0f,
        ) {
            input.surfaceTexture.getTransformMatrix(matrix)
            GLES20.glUniformMatrix4fv(uSTMatrix, 1, false, matrix, 0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, input.texture)
            // 圆角裁剪（GL 坐标 y 翻转：传入矩形以左上原点表达，此处换算）
            if (cornerRadius > 0f) {
                GLES20.glUniform1i(uClipEnabled, 1)
                GLES20.glUniform4f(
                    uClipRect,
                    x0, outHeight - y1, x1, outHeight - y0,
                )
                GLES20.glUniform1f(uClipRadius, cornerRadius)
            } else {
                GLES20.glUniform1i(uClipEnabled, 0)
            }
            // 像素坐标 → 裁剪坐标（GL 左下原点；纹理翻转由 uSTMatrix 处理，勿重复翻转）
            fun px(v: Float) = v / outWidth * 2f - 1f
            fun py(v: Float) = v / outHeight * 2f - 1f
            val positions = floatBuffer(
                px(x0), py(y0), px(x1), py(y0), px(x0), py(y1), px(x1), py(y1),
            )
            val coords = floatBuffer(u0, 0f, u1, 0f, u0, 1f, u1, 1f)
            GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, positions)
            GLES20.glEnableVertexAttribArray(aPos)
            GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, 0, coords)
            GLES20.glEnableVertexAttribArray(aTexCoord)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }

        fun drawInput(input: Input, x0: Float, x1: Float, y0: Float, y1: Float) =
            drawInputUV(input, x0, x1, y0, y1, u0 = 0f, u1 = 1f)

        fun composeFrame(fullRedraw: Boolean) {
            // 待切应用屏：目标屏出帧即切（帧号前进 / 已有内容 600ms 兜底 / 2s 超时兜底）
            pendingTargetHi?.let { targetHi ->
                val nowMs = android.os.SystemClock.uptimeMillis()
                val target = if (targetHi) hi else app
                val advanced = target != null && target.frameCount > pendingBaseFrame
                if (advanced ||
                    (target?.hasContent == true && nowMs - pendingSince >= 600L) ||
                    nowMs - pendingSince >= 2_000L
                ) {
                    activeAppHi = targetHi
                    pendingTargetHi = null
                }
            }
            GLES20.glViewport(0, 0, outWidth, outHeight)
            GLES20.glClearColor(0.04f, 0.07f, 0.09f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            // 只对有新帧的输入 updateTexImage；已有内容的输入沿用旧纹理（画面持久）。
            // 两块应用屏都持续消费（非活动屏也保持最新，切换时不闪旧帧）。
            if (dock.hasNewFrame) {
                dock.hasNewFrame = false
                dock.hasContent = true
                dock.frameCount++
                dock.surfaceTexture.updateTexImage()
            }
            if (app.hasNewFrame) {
                app.hasNewFrame = false
                app.hasContent = true
                app.frameCount++
                app.surfaceTexture.updateTexImage()
            }
            hi?.let {
                if (it.hasNewFrame) {
                    it.hasNewFrame = false
                    it.hasContent = true
                    it.frameCount++
                    it.surfaceTexture.updateTexImage()
                }
            }
            map?.let {
                if (it.hasNewFrame) {
                    it.hasNewFrame = false
                    it.hasContent = true
                    it.frameCount++
                    it.surfaceTexture.updateTexImage()
                }
            }
            val activeInput = if (activeAppHi) hi else app
            if (dockAtBottom) {
                // 竖屏：dock 横条贴底（GL 坐标 y=0 为底边），app 在其余区域
                if (dock.hasContent) drawInput(dock, 0f, outWidth.toFloat(), 0f, dockH.toFloat())
                if (activeInput?.hasContent == true) {
                    drawInput(activeInput, 0f, outWidth.toFloat(), dockH.toFloat(), outHeight.toFloat())
                }
            } else {
                if (activeInput?.hasContent == true) {
                    drawInput(activeInput, dockW.toFloat(), outWidth.toFloat(), 0f, outHeight.toFloat())
                }
                // 地图层叠在应用区之上（HIDDEN 不画）：
                //  FULLSCREEN=整个应用区（采样全缓冲）；SPLIT=左半区域采样缓冲中央带区
                //  （地图应用把车辆/当前位置渲染在画面中心，裁两侧即得到以车为中心的地图）。
                val layout = tileLayout
                if (map?.hasContent == true && layout != TileLayout.HIDDEN) {
                    if (layout == TileLayout.FULLSCREEN) {
                        drawInput(map!!, dockW.toFloat(), outWidth.toFloat(), 0f, outHeight.toFloat())
                    } else {
                        // 卡片形态：四周留边 + 圆角（与音乐卡一致）。
                        // 中缝=TILE_MARGIN（16）：tile 右缘与音乐卡左缘各收半边距（8），
                        // 否则两边各 16px 叠加出 32px 的中缝、比外侧 16px 边距宽一倍不协调。
                        // tileSlideFraction：随 pager 滑动把 tile 平移出/入左侧（视觉跟手）。
                        val half = (outWidth - dockW) / 2f
                        val tileW = half - TILE_MARGIN - TILE_MARGIN / 2f
                        val slide = tileSlideFraction * (outWidth - dockW)
                        drawInputUV(
                            map!!,
                            dockW + TILE_MARGIN - slide, dockW + TILE_MARGIN + tileW - slide,
                            TILE_MARGIN, outHeight - TILE_MARGIN,
                            u0 = 0.25f, u1 = 0.75f,
                            cornerRadius = TILE_CORNER_RADIUS,
                        )
                    }
                }
                // dock 最后绘制（最上层）：tile 滑动经过 dock 区域时从其下方穿过
                if (dock.hasContent) drawInput(dock, 0f, dockW.toFloat(), 0f, outHeight.toFloat())
            }
            if (fullRedraw || dock.hasContent || app.hasContent || hi?.hasContent == true || map?.hasContent == true) {
                EGL14.eglSwapBuffers(display, outputSurface)
            }
        }

        // 初始黑帧（只清屏，不碰空纹理）+ 交付输入 Surface + 放行 start()
        GLES20.glViewport(0, 0, outWidth, outHeight)
        GLES20.glClearColor(0.04f, 0.07f, 0.09f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        EGL14.eglSwapBuffers(display, outputSurface)
        onSurfacesReady?.invoke(dock.surface, app.surface, hi?.surface, map?.surface)
        startLatch?.countDown()

        // ---- 合成循环 ----
        // 有输入新帧：立即合成。
        // 无新帧（界面静止，如启动器/阅读页/地图停靠）：以 33ms 心跳重复合成最后一帧内容——
        // 否则编码器无输入、视频流停摆，接收端画面冻结（表现为"桌面卡死/不可交互"，
        // 2026-10-09 实测踩坑：触摸其实送到了，只是没有帧能反映出来）。
        try {
            var lastComposeAt = System.currentTimeMillis()
            while (running.get()) {
                val hasNew = dock.hasNewFrame || app.hasNewFrame
                val now = System.currentTimeMillis()
                if (hasNew || now - lastComposeAt >= 33) {
                    composeFrame(fullRedraw = false)
                    lastComposeAt = now
                } else {
                    Thread.sleep(8)
                }
            }
        } catch (_: InterruptedException) {
        } finally {
            runCatching { dock.surface.release() }
            runCatching { app.surface.release() }
            runCatching { dock.surfaceTexture.release() }
            runCatching { app.surfaceTexture.release() }
            map?.let {
                runCatching { it.surface.release() }
                runCatching { it.surfaceTexture.release() }
            }
            hi?.let {
                runCatching { it.surface.release() }
                runCatching { it.surfaceTexture.release() }
            }
            runCatching {
                EGL14.eglMakeCurrent(
                    display,
                    EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_CONTEXT,
                )
            }
            runCatching { EGL14.eglDestroySurface(display, outputSurface) }
            runCatching { EGL14.eglDestroyContext(display, context) }
            runCatching { EGL14.eglTerminate(display) }
        }
    }

    /** [oes]=true：外部纹理采样（SurfaceTexture）；false：普通 2D 采样（壁纸位图）。 */
    private fun buildProgram(oes: Boolean): Int {
        val fragment = if (oes) {
            """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTexCoord;
            uniform samplerExternalOES uTexture;
            uniform int uClipEnabled;
            uniform vec4 uClipRect;
            uniform float uClipRadius;
            void main() {
                if (uClipEnabled == 1) {
                    vec2 p = gl_FragCoord.xy;
                    vec2 half2 = vec2(uClipRadius);
                    vec2 c = clamp(p, uClipRect.xy + half2, uClipRect.zw - half2);
                    if (distance(p, c) > uClipRadius) discard;
                }
                gl_FragColor = texture2D(uTexture, vTexCoord);
            }
            """.trimIndent()
        } else {
            """
            precision mediump float;
            varying vec2 vTexCoord;
            uniform sampler2D uTexture;
            void main() {
                gl_FragColor = texture2D(uTexture, vTexCoord);
            }
            """.trimIndent()
        }
        val vertex = """
            attribute vec2 aPos;
            attribute vec2 aTexCoord;
            uniform mat4 uSTMatrix;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = vec4(aPos, 0.0, 1.0);
                vTexCoord = (uSTMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;
            }
        """.trimIndent()

        fun compile(type: Int, source: String): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)
            return shader
        }

        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, compile(GLES20.GL_VERTEX_SHADER, vertex))
        GLES20.glAttachShader(program, compile(GLES20.GL_FRAGMENT_SHADER, fragment))
        GLES20.glLinkProgram(program)
        return program
    }

    private fun floatBuffer(vararg values: Float): FloatBuffer =
        ByteBuffer.allocateDirect(values.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(values)
                position(0)
            }

    private fun checkEgl(ok: Boolean, stage: String) {
        if (!ok) {
            Log.e(TAG, "EGL 失败：$stage")
            throw IllegalStateException("EGL $stage")
        }
    }
}
